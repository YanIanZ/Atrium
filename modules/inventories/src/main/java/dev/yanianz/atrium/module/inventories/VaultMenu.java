package dev.yanianz.atrium.module.inventories;

import dev.yanianz.atrium.core.menu.Menu;
import dev.yanianz.atrium.core.menu.MenuHolder;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * One open personal vault, built on {@code ctx.menus()} with every slot declared as an input slot, so the menu
 * engine cancels anything it cannot return safely.
 *
 * <p>Dupe safety: the contents are <em>drained</em> from the menu before the row is written, so the engine's
 * return-on-close cannot hand the same stack out a second time. A vault is only written after its stored
 * contents were loaded ({@code ready}); closing earlier hands the items back and leaves the row untouched, so a
 * slow read can never wipe a vault. A failed write stores the items as a pending return for the owner instead
 * of dropping them.
 *
 * <p>All player access happens on the viewer's thread; storage runs on the I/O executor.
 */
final class VaultMenu {
    private final ModuleContext ctx;
    private final VaultStore store;
    private final PendingReturnStore returns;
    private final Supplier<InventoriesConfig> config;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private volatile boolean closing;

    private static final class Session {
        final Player player;
        final int vault;
        final int slots;
        final AtomicBoolean finished = new AtomicBoolean();
        volatile @Nullable Inventory inventory;
        volatile boolean ready;
        volatile boolean broken;

        Session(Player player, int vault, int rows) {
            this.player = player;
            this.vault = vault;
            this.slots = VaultLayout.slots(rows);
        }
    }

    VaultMenu(ModuleContext ctx, VaultStore store, PendingReturnStore returns,
            Supplier<InventoriesConfig> config) {
        this.ctx = ctx;
        this.store = store;
        this.returns = returns;
        this.config = config;
    }

    /** Opens a vault for the player. Player thread. */
    void open(Player player, int vault) {
        if (closing) {
            return;
        }
        int rows = VaultLayout.rows(dev.yanianz.atrium.core.permission.Permissions.limit(player,
                "atrium.pv.size", config.get().defaultVaultRows));
        // A previous vault is closed by the menu engine, whose handler saves it; only the reference is dropped.
        sessions.remove(player.getUniqueId());
        Session session = new Session(player, vault, rows);
        Menu.Builder builder = Menu.builder(
                        ctx.messages().render(player, "inventories.vault-title", Msg.number("vault", vault)), rows)
                .filler(false)
                .playerInventoryInput(true)
                .onClose((viewer, inventory) -> closed(session, inventory));
        for (int slot = 0; slot < session.slots; slot++) {
            builder.input(slot);
        }
        Menu menu = builder.build();
        sessions.put(player.getUniqueId(), session);
        ctx.menus().open(player, menu);
        MenuHolder holder = MenuHolder.of(player.getOpenInventory());
        if (holder == null || holder.menu() != menu) {
            // Another plugin refused to open the menu; nothing was put in yet.
            sessions.remove(player.getUniqueId(), session);
            session.finished.set(true);
            return;
        }
        session.inventory = holder.getInventory();
        ctx.messages().send(player, "inventories.vault-opened", Msg.number("vault", vault));
        load(session, holder.getInventory());
    }

    private void load(Session session, Inventory inventory) {
        UUID owner = session.player.getUniqueId();
        store.load(owner, session.vault).whenComplete((stored, error) ->
                onPlayerThread(session, () -> {
                    if (session.finished.get() || session.inventory != inventory) {
                        return;
                    }
                    if (error != null) {
                        ctx.logger().error("Reading vault {} of {} failed", session.vault, owner, error);
                        failLoad(session, "inventories.vault-load-failed");
                        return;
                    }
                    List<ItemStack> items = List.of();
                    List<ItemStack> overflow = List.of();
                    if (stored.isPresent()) {
                        try {
                            VaultLayout.Fit<ItemStack> fit = VaultItems.decode(stored.get().items(), inventory.getSize());
                            items = fit.kept();
                            overflow = fit.overflow();
                        } catch (IllegalArgumentException e) {
                            ctx.logger().error("Vault {} of {} is unreadable and was left untouched",
                                    session.vault, owner, e);
                            failLoad(session, "inventories.vault-corrupt");
                            return;
                        }
                    }
                    for (int i = 0; i < items.size() && i < inventory.getSize(); i++) {
                        inventory.setItem(i, items.get(i));
                    }
                    session.ready = true;
                    if (!overflow.isEmpty()) {
                        give(session.player, overflow);
                        ctx.messages().send(session.player, "inventories.vault-shrunk",
                                Msg.number("count", overflow.size()));
                    }
                }));
    }

    /** A vault that could not be read is closed without saving, so the row keeps its items. */
    private void failLoad(Session session, String messageKey) {
        session.broken = true;
        ctx.messages().send(session.player, messageKey);
        ctx.menus().close(session.player);
    }

    /** Close handler (player thread), called before the menu engine returns input slots. */
    private void closed(Session session, Inventory inventory) {
        if (!session.finished.compareAndSet(false, true)) {
            return;
        }
        sessions.remove(session.player.getUniqueId(), session);
        List<ItemStack> items = drain(inventory);
        if (session.broken || !session.ready) {
            // Nothing was loaded, so the row must not be overwritten with an empty vault.
            if (session.player.isOnline()) {
                give(session.player, items);
            } else {
                persistFallback(session, items);
            }
            return;
        }
        save(session, items, false);
    }

    private void save(Session session, List<ItemStack> items, boolean blocking) {
        VaultLayout.Fit<ItemStack> fit = VaultLayout.fit(items, session.slots);
        byte[] bytes = VaultItems.encode(fit.kept());
        UUID owner = session.player.getUniqueId();
        var future = store.save(owner, session.vault, session.slots / VaultLayout.COLUMNS, session.slots, bytes)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        ctx.logger().error("Saving vault {} of {} failed; the items are queued as a return",
                                session.vault, owner, error);
                        persistFallback(session, fit.kept());
                    }
                });
        if (blocking) {
            try {
                future.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                ctx.logger().warn("Waiting for vault {} of {} to be saved failed", session.vault, owner, e);
            }
        }
    }

    /** Items that could not be written are owed to the owner and handed out on their next join. */
    private void persistFallback(Session session, List<ItemStack> slots) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : slots) {
            if (item != null && !item.isEmpty()) {
                items.add(item.clone());
            }
        }
        if (items.isEmpty()) {
            return;
        }
        byte[] bytes = ItemStack.serializeItemsAsBytes(items.toArray(new ItemStack[items.size()]));
        PendingReturnStore.Draft draft = new PendingReturnStore.Draft(session.player.getUniqueId(),
                session.player.getName(), System.currentTimeMillis(), "vault-save",
                "vault " + session.vault, items.size(), bytes);
        returns.insert(draft).whenComplete((id, error) -> {
            if (error != null) {
                ctx.logger().error("Could not queue the contents of vault {} of {}. Base64 item data: {}",
                        session.vault, session.player.getUniqueId(),
                        Base64.getEncoder().encodeToString(bytes), error);
            }
        });
    }

    /** Takes every stack out of the menu so nothing can be returned twice. */
    private static List<ItemStack> drain(Inventory inventory) {
        List<ItemStack> items = new ArrayList<>();
        try {
            for (ItemStack item : inventory.getContents()) {
                items.add(item == null ? null : item.clone());
            }
        } finally {
            inventory.clear();
        }
        return items;
    }

    /** Gives items back on the owner's thread; anything that does not fit is dropped, never deleted. */
    private void give(Player player, List<ItemStack> items) {
        List<ItemStack> copy = new ArrayList<>();
        for (ItemStack item : items) {
            if (item != null && !item.isEmpty()) {
                copy.add(item);
            }
        }
        if (copy.isEmpty() || !player.isOnline()) {
            return;
        }
        for (ItemStack item : copy) {
            var leftover = player.getInventory().addItem(item);
            leftover.values().forEach(stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
        }
    }

    private void onPlayerThread(Session session, Runnable task) {
        Player player = session.player;
        if (!player.isOnline()) {
            return;
        }
        try {
            ctx.scheduler().entity(player, task, null);
        } catch (RuntimeException error) {
            ctx.logger().debug("Could not schedule the vault load of {}", player.getName(), error);
        }
    }

    /** Module disable or plugin shutdown: every open vault is written before the menu engine closes it. */
    void shutdown() {
        closing = true;
        for (Session session : List.copyOf(sessions.values())) {
            sessions.remove(session.player.getUniqueId(), session);
            if (!session.finished.compareAndSet(false, true)) {
                continue;
            }
            Inventory inventory = session.inventory;
            List<ItemStack> items = inventory == null ? List.of() : drain(inventory);
            if (session.broken || !session.ready) {
                // The row is untouched; whatever the player put in is owed to them.
                persistFallback(session, items);
                continue;
            }
            // Storage is all that is left on shutdown; wait for it so a restart cannot lose the edit.
            save(session, items, true);
        }
    }
}
