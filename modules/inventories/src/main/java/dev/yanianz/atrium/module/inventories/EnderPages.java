package dev.yanianz.atrium.module.inventories;

import dev.yanianz.atrium.api.profile.ActiveProfileService;
import dev.yanianz.atrium.core.menu.ItemBuilder;
import dev.yanianz.atrium.core.menu.Menu;
import dev.yanianz.atrium.core.menu.MenuHolder;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.permission.Permissions;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The ender chest as pages: an ender chest block and {@code /enderchest} open page one of the player's own
 * pages instead of the vanilla 27 slots. A page is five rows of storage with a row of buttons under it; the
 * number of pages comes from {@code atrium.enderchest.pages.<n>}.
 *
 * <p>Where the items live: a page is one row of the vault table ({@link VaultStore}), so it is written in one
 * statement like a vault. The owner column holds a key made from the player and, when the server has profiles
 * ({@link ActiveProfileService}), the active profile: every profile of a player has its own pages, as the vanilla
 * ender chest does there. While profiles are on but the active one is not known yet nothing is opened, so
 * items can never land in the wrong profile.
 *
 * <p>Dupe safety follows {@link VaultMenu}: the storage slots are drained before the row is written, a page is
 * only written after its stored contents were loaded, and a failed write is kept as a pending return. In
 * addition every read of a page waits for the last write of the same owner on this server, so flipping back to
 * a page cannot show it as it was before its own save. Nothing is ever moved automatically: items still in
 * the vanilla ender chest stay there and a button opens it so the player can move them by hand.
 *
 * <p>All player access happens on the viewer's thread; storage runs on the I/O executor.
 */
final class EnderPages implements Listener {
    static final String PAGE_LIMIT = "atrium.enderchest.pages";
    /** Storage rows of one page; the sixth row of the chest holds the buttons. */
    static final int ROWS = 5;
    static final int SLOTS = ROWS * VaultLayout.COLUMNS;
    static final int MAX_PAGES = 9;

    private static final int MENU_ROWS = ROWS + 1;
    private static final int PREVIOUS = SLOTS;
    private static final int OLD_CHEST = SLOTS + 2;
    private static final int CLOSE = SLOTS + 4;
    private static final int INFO = SLOTS + 5;
    private static final int NEXT = SLOTS + 8;

    private final ModuleContext ctx;
    private final VaultStore store;
    private final PendingReturnStore returns;
    private final Supplier<InventoriesConfig> config;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    /** Last write per owner key; a read of that owner's pages waits for it. */
    private final Map<UUID, CompletableFuture<Void>> writes = new ConcurrentHashMap<>();
    /** Players about to open the vanilla ender chest on purpose (the "old ender chest" button). */
    private final Set<UUID> vanillaOnce = ConcurrentHashMap.newKeySet();
    private volatile boolean closing;

    private static final class Session {
        final Player player;
        final UUID owner;
        final int page;
        final int pages;
        final AtomicBoolean finished = new AtomicBoolean();
        volatile @Nullable Inventory inventory;
        volatile boolean ready;
        volatile boolean broken;

        Session(Player player, UUID owner, int page, int pages) {
            this.player = player;
            this.owner = owner;
            this.page = page;
            this.pages = pages;
        }
    }

    EnderPages(ModuleContext ctx, VaultStore store, PendingReturnStore returns, Supplier<InventoriesConfig> config) {
        this.ctx = ctx;
        this.store = store;
        this.returns = returns;
        this.config = config;
    }

    private boolean enabled() {
        return !closing && config.get().enderPagesEnabled;
    }

    /** Pages this player may use: {@code atrium.enderchest.pages.<n>}, never fewer than the configured default. */
    static int pages(Player player, InventoriesConfig config) {
        return Math.clamp(Permissions.limit(player, PAGE_LIMIT, Math.max(1, config.defaultEnderPages)), 1, MAX_PAGES);
    }

    /** The storage key of one player's pages: the player alone, or the player within one profile. */
    static UUID ownerKey(UUID player, @Nullable UUID profile) {
        String name = profile == null ? "atrium-ender:" + player : "atrium-ender:" + player + ":" + profile;
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    // ---- entry points ----

    /** An ender chest block opens the pages; the block itself is never opened, so its lid stays shut. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (!enabled() || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.ENDER_CHEST) {
            return;
        }
        if (event.useInteractedBlock() == Event.Result.DENY) {
            return; // a protection plugin said no
        }
        Player player = event.getPlayer();
        if (player.isSneaking() && event.getItem() != null) {
            return; // placing a block against the chest
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        if (event.getHand() == EquipmentSlot.HAND) {
            open(player, 1, true);
        }
    }

    /** Anything else that opens the player's own vanilla ender chest ({@code /enderchest}, another plugin). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!enabled() || event.getInventory().getType() != InventoryType.ENDER_CHEST) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!event.getInventory().equals(player.getEnderChest())) {
            return; // staff looking into someone else's ender chest
        }
        if (vanillaOnce.remove(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        open(player, 1, true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        vanillaOnce.remove(event.getPlayer().getUniqueId());
    }

    /** Opens one page. Any thread; the menu is opened on the player's thread once the storage key is known. */
    void open(Player player, int page, boolean sound) {
        if (!enabled()) {
            return;
        }
        owner(player).whenComplete((owner, error) -> onPlayerThread(player, () -> {
            if (error != null || owner == null || owner.isEmpty()) {
                if (error != null) {
                    ctx.logger().warn("Reading the active profile of {} failed", player.getUniqueId(), error);
                }
                ctx.messages().send(player, "inventories.pages-no-profile");
                return;
            }
            show(player, owner.get(), page, sound);
        }));
    }

    private CompletableFuture<Optional<UUID>> owner(Player player) {
        UUID uuid = player.getUniqueId();
        Optional<ActiveProfileService> profiles = ctx.services().get(ActiveProfileService.class);
        if (profiles.isEmpty() || !profiles.get().enabled()) {
            return CompletableFuture.completedFuture(Optional.of(ownerKey(uuid, null)));
        }
        Optional<ActiveProfileService.ActiveProfile> cached = profiles.get().cached(uuid);
        if (cached.isPresent()) {
            return CompletableFuture.completedFuture(Optional.of(ownerKey(uuid, cached.get().id())));
        }
        return profiles.get().refresh(uuid).thenApply(profile -> profile.map(active -> ownerKey(uuid, active.id())));
    }

    /** Player thread. */
    private void show(Player player, UUID owner, int requested, boolean sound) {
        if (!enabled() || !player.isOnline()) {
            return;
        }
        int pages = pages(player, config.get());
        int page = Math.clamp(requested, 1, pages);
        // A page that is still open is closed by the menu engine, whose handler saves it; only the reference is dropped.
        sessions.remove(player.getUniqueId());
        Session session = new Session(player, owner, page, pages);
        Menu.Builder builder = Menu.builder(ctx.messages().render(player, "inventories.pages-title",
                        Msg.number("page", page), Msg.number("pages", pages)), MENU_ROWS)
                .playerInventoryInput(true)
                .onClose((viewer, inventory) -> closed(session, inventory));
        for (int slot = 0; slot < SLOTS; slot++) {
            builder.input(slot);
        }
        if (page > 1) {
            builder.item(PREVIOUS, icon(player, Material.ARROW, "inventories.pages-previous", null),
                    click -> open(player, page - 1, false));
        }
        if (page < pages) {
            builder.item(NEXT, icon(player, Material.ARROW, "inventories.pages-next", null),
                    click -> open(player, page + 1, false));
        } else if (pages < MAX_PAGES) {
            builder.item(NEXT, icon(player, Material.GRAY_DYE, "inventories.pages-locked", "inventories.pages-locked-lore"));
        }
        builder.item(CLOSE, icon(player, Material.BARRIER, "inventories.pages-close", null),
                click -> ctx.menus().close(player));
        builder.item(INFO, ItemBuilder.of(Material.ENDER_EYE)
                .name(ctx.messages().render(player, "inventories.pages-info", Msg.number("page", page),
                        Msg.number("pages", pages)))
                .lore(ctx.messages().render(player, "inventories.pages-info-lore")).build());
        if (!player.getEnderChest().isEmpty()) {
            builder.item(OLD_CHEST, icon(player, Material.ENDER_CHEST, "inventories.pages-old", "inventories.pages-old-lore"),
                    click -> {
                        vanillaOnce.add(player.getUniqueId());
                        player.openInventory(player.getEnderChest());
                    });
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
        if (sound) {
            player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.5f, 1.0f);
        }
        load(session, holder.getInventory());
    }

    private ItemStack icon(Player viewer, Material material, String nameKey, @Nullable String loreKey) {
        ItemBuilder builder = ItemBuilder.of(material).name(ctx.messages().render(viewer, nameKey));
        if (loreKey != null) {
            builder.lore(ctx.messages().render(viewer, loreKey));
        }
        return builder.build();
    }

    // ---- storage ----

    private void load(Session session, Inventory inventory) {
        // After the last write of this owner here: a page flipped away from a moment ago must be read as saved.
        CompletableFuture<Void> pending = writes.getOrDefault(session.owner, CompletableFuture.completedFuture(null));
        pending.handle((ignored, error) -> null)
                .thenCompose(ignored -> store.load(session.owner, session.page))
                .whenComplete((stored, error) -> onPlayerThread(session.player, () -> {
                    if (session.finished.get() || session.inventory != inventory) {
                        return;
                    }
                    if (error != null) {
                        ctx.logger().error("Reading ender chest page {} of {} failed", session.page,
                                session.player.getUniqueId(), error);
                        failLoad(session, "inventories.pages-load-failed");
                        return;
                    }
                    List<ItemStack> items = List.of();
                    List<ItemStack> overflow = List.of();
                    if (stored.isPresent()) {
                        try {
                            VaultLayout.Fit<ItemStack> fit = VaultItems.decode(stored.get().items(), SLOTS);
                            items = fit.kept();
                            overflow = fit.overflow();
                        } catch (IllegalArgumentException e) {
                            ctx.logger().error("Ender chest page {} of {} is unreadable and was left untouched",
                                    session.page, session.player.getUniqueId(), e);
                            failLoad(session, "inventories.pages-corrupt");
                            return;
                        }
                    }
                    for (int i = 0; i < items.size() && i < SLOTS; i++) {
                        inventory.setItem(i, items.get(i));
                    }
                    session.ready = true;
                    if (!overflow.isEmpty()) {
                        give(session.player, overflow);
                    }
                }));
    }

    /** A page that could not be read is closed without saving, so the row keeps its items. */
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
            // Nothing was loaded, so the row must not be overwritten with an empty page.
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
        VaultLayout.Fit<ItemStack> fit = VaultLayout.fit(items, SLOTS);
        byte[] bytes = VaultItems.encode(fit.kept());
        CompletableFuture<Void> future = store.save(session.owner, session.page, ROWS, SLOTS, bytes)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        ctx.logger().error("Saving ender chest page {} of {} failed; the items are queued as a return",
                                session.page, session.player.getUniqueId(), error);
                        persistFallback(session, fit.kept());
                    }
                });
        writes.put(session.owner, future);
        future.whenComplete((ignored, error) -> writes.remove(session.owner, future));
        if (blocking) {
            try {
                future.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                ctx.logger().warn("Waiting for ender chest page {} of {} to be saved failed", session.page,
                        session.player.getUniqueId(), e);
            }
        }
    }

    /** Items that could not be written are owed to the player and handed out on their next join. */
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
                session.player.getName(), System.currentTimeMillis(), "ender-save",
                "ender chest page " + session.page, items.size(), bytes);
        returns.insert(draft).whenComplete((id, error) -> {
            if (error != null) {
                ctx.logger().error("Could not queue the contents of ender chest page {} of {}. Base64 item data: {}",
                        session.page, session.player.getUniqueId(), Base64.getEncoder().encodeToString(bytes), error);
            }
        });
    }

    /** Takes every stack out of the storage slots so nothing can be returned twice; the buttons stay. */
    private static List<ItemStack> drain(Inventory inventory) {
        List<ItemStack> items = new ArrayList<>(SLOTS);
        for (int slot = 0; slot < SLOTS && slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            items.add(item == null ? null : item.clone());
            inventory.setItem(slot, null);
        }
        return items;
    }

    /** Gives items back on the player's thread; anything that does not fit is dropped, never deleted. */
    private void give(Player player, List<ItemStack> items) {
        if (!player.isOnline()) {
            return;
        }
        for (ItemStack item : items) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            var leftover = player.getInventory().addItem(item);
            leftover.values().forEach(stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
        }
    }

    private void onPlayerThread(Player player, Runnable task) {
        if (!player.isOnline()) {
            return;
        }
        try {
            ctx.scheduler().entity(player, task, null);
        } catch (RuntimeException error) {
            ctx.logger().debug("Could not schedule ender chest work for {}", player.getName(), error);
        }
    }

    /** Module disable or plugin shutdown: every open page is written before the menu engine closes it. */
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
