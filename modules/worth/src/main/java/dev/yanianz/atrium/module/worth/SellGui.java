package dev.yanianz.atrium.module.worth;

import dev.yanianz.atrium.core.menu.Menu;
import dev.yanianz.atrium.core.menu.MenuHolder;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * A {@code ctx.menus()} chest whose slots are all input slots: the player drops items in, and closing it sells
 * what is sellable and hands back the rest. The items are taken out of the menu ({@link #drain}) before anything
 * is sold or paid, so the menu engine's return-on-close never hands them out a second time. Every exit path
 * (close, disconnect, death, module shutdown) finishes a session exactly once.
 */
final class SellGui implements Listener {
    private final ModuleContext ctx;
    private final SellService sell;
    private final ItemReturns returns;
    private final Supplier<WorthConfig> config;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private volatile boolean closing;

    private static final class Session {
        final Player player;
        final AtomicBoolean finished = new AtomicBoolean();
        /** The shown menu inventory; set once the menu is open. */
        volatile @Nullable Inventory inventory;

        Session(Player player) {
            this.player = player;
        }
    }

    SellGui(ModuleContext ctx, SellService sell, ItemReturns returns, Supplier<WorthConfig> config) {
        this.ctx = ctx;
        this.sell = sell;
        this.returns = returns;
        this.config = config;
    }

    /** Number of rows of the sell chest, clamped to 1-6. */
    static int rows(int configured) {
        return Math.max(1, Math.min(6, configured));
    }

    /** Player thread. */
    void open(Player player) {
        if (closing) return;
        if (sell.creativeBlocked(player)) {
            ctx.messages().send(player, "worth.creative");
            return;
        }
        Session previous = sessions.remove(player.getUniqueId());
        if (previous != null) {
            finishReturning(previous);
        }
        int rows = rows(config.get().sellGuiRows);
        Session session = new Session(player);
        Menu.Builder builder = Menu.builder(ctx.messages().render(player, "worth.gui-title"), rows)
                .filler(false)
                .playerInventoryInput(true)
                .onClose((viewer, inventory) -> closed(session, inventory));
        for (int slot = 0; slot < rows * 9; slot++) {
            builder.input(slot);
        }
        Menu menu = builder.build();
        sessions.put(player.getUniqueId(), session);
        ctx.menus().open(player, menu);
        MenuHolder holder = MenuHolder.of(player.getOpenInventory());
        if (holder == null || holder.menu() != menu) {
            // Opening was refused (another plugin): nothing was put in yet.
            sessions.remove(player.getUniqueId(), session);
            session.finished.set(true);
            return;
        }
        session.inventory = holder.getInventory();
        ctx.messages().send(player, "worth.gui-opened");
    }

    /** Close handler of the sell menu (player's thread), before the menu returns its input slots. */
    private void closed(Session session, Inventory inventory) {
        if (!session.finished.compareAndSet(false, true)) return;
        sessions.remove(session.player.getUniqueId(), session);
        List<ItemStack> items = drain(inventory);
        Player player = session.player;
        boolean abnormal = closing || player.isDead() || !player.isOnline();
        if (abnormal) {
            returns.giveNow(player, items);
        } else {
            sell.sellDetached(player, items);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        // Before the menu engine closes the menu on quit: a disconnect never sells, it hands everything back.
        Session session = sessions.remove(event.getPlayer().getUniqueId());
        if (session != null) {
            finishReturning(session);
        }
    }

    /** Ends a session by giving everything back (no sale). Player thread. */
    private void finishReturning(Session session) {
        if (!session.finished.compareAndSet(false, true)) return;
        Inventory inventory = session.inventory;
        if (inventory != null) returns.giveNow(session.player, drain(inventory));
    }

    /** Takes everything out of the menu; it is empty afterwards so nothing can be taken twice. */
    private static List<ItemStack> drain(Inventory inventory) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : inventory.getContents()) {
            if (item != null && !item.isEmpty()) items.add(item.clone());
        }
        inventory.clear();
        return items;
    }

    /** Module disable: hand back or persist every open GUI. Scheduled tasks may never run after this. */
    void shutdown() {
        closing = true;
        for (Session session : List.copyOf(sessions.values())) {
            sessions.remove(session.player.getUniqueId(), session);
            if (!session.finished.compareAndSet(false, true)) continue;
            Inventory inventory = session.inventory;
            List<ItemStack> items = inventory == null ? List.of() : drain(inventory);
            Player player = session.player;
            if (Bukkit.isOwnedByCurrentRegion(player) && player.isOnline() && !player.isDead()) {
                returns.giveNow(player, items);
            } else {
                returns.persistBlocking(player.getUniqueId(), items);
            }
            // The menu engine closes the (now empty) menu when the module is disabled.
        }
    }
}
