package dev.yanianz.atrium.module.vanish;

import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.EnderChest;
import org.bukkit.block.Lidded;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.Lootable;
import org.jspecify.annotations.Nullable;

/**
 * Opens lidded containers (chests, barrels, shulker boxes) of vanished players without animation or sound:
 * the player sees a copy that is refreshed from the real container. Vanilla never moves items into or out of
 * the copy; with {@code atrium.vanish.silentchest.modify} a click becomes an explicit edit of the real
 * container, executed on the region that owns the block and read from the live inventory at that moment.
 * Ender chests open the player's own ender chest directly (that never animates the block).
 */
final class SilentContainers implements Listener, TaskHandle {
    private static final long REFRESH_TICKS = 10;
    private static final double MAX_DISTANCE_SQUARED = 8 * 8;

    private final ModuleContext ctx;
    private final VanishManager manager;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private volatile boolean cancelled;

    private static final class Session implements InventoryHolder {
        final Player viewer;
        final Block block;
        final boolean modify;
        final boolean shulker;
        final int liveSize;
        final AtomicBoolean busy = new AtomicBoolean();
        volatile boolean closed;
        volatile TaskHandle refresh = TaskHandle.NOOP;
        Inventory display;

        Session(Player viewer, Block block, boolean modify, boolean shulker, int liveSize) {
            this.viewer = viewer;
            this.block = block;
            this.modify = modify;
            this.shulker = shulker;
            this.liveSize = liveSize;
        }

        @Override
        public Inventory getInventory() {
            return display;
        }

        Location location() {
            return block.getLocation();
        }

        void stop() {
            closed = true;
            refresh.cancel();
        }
    }

    SilentContainers(ModuleContext ctx, VanishManager manager) {
        this.ctx = ctx;
        this.manager = manager;
    }

    // ---- opening ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (cancelled || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || event.useInteractedBlock() == Event.Result.DENY) {
            return;
        }
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        if (block == null || !manager.silentChestEnabled(player)) {
            return;
        }
        // Sneaking with an item uses the item instead of opening the block.
        if (player.isSneaking() && (!player.getInventory().getItemInMainHand().isEmpty()
                || !player.getInventory().getItemInOffHand().isEmpty())) {
            return;
        }
        if (!Bukkit.isOwnedByCurrentRegion(block)) {
            return;
        }
        BlockState state = block.getState(false);
        if (state instanceof EnderChest) {
            event.setCancelled(true);
            player.openInventory(player.getEnderChest());
            return;
        }
        if (!(state instanceof Lidded) || !(state instanceof Container container)) {
            return;
        }
        if (container.isLocked()) {
            return; // vanilla refuses locked containers without animating them
        }
        if (container instanceof Lootable lootable && lootable.hasLootTable()) {
            // Opening would generate the loot; a copy cannot do that, and a real open is not silent.
            event.setCancelled(true);
            ctx.messages().send(player, "vanish.silent-loot");
            return;
        }
        Inventory live = container.getInventory();
        boolean modify = player.hasPermission(VanishManager.PERM_SILENT_MODIFY);
        Session session = new Session(player, block, modify, state instanceof ShulkerBox, live.getSize());
        int size = Math.max(9, Math.min(54, (live.getSize() + 8) / 9 * 9));
        session.display = Bukkit.createInventory(session, size,
                ctx.messages().render(player, modify ? "vanish.silent-title-modify" : "vanish.silent-title"));
        copy(live, session.display);
        event.setCancelled(true);
        player.openInventory(session.display);
        if (player.getOpenInventory().getTopInventory() != session.display) {
            return; // another plugin cancelled the opening
        }
        Session previous = sessions.put(player.getUniqueId(), session);
        if (previous != null && previous != session) {
            previous.stop();
        }
        if (!modify) {
            ctx.messages().send(player, "vanish.silent-readonly");
        }
        scheduleRefresh(session);
    }

    // ---- refreshing ----

    private void scheduleRefresh(Session s) {
        if (cancelled || s.closed) {
            return;
        }
        s.refresh = ctx.scheduler().regionLater(s.location(), () -> refresh(s), REFRESH_TICKS);
        if (s.closed || cancelled) {
            s.refresh.cancel();
        }
    }

    /** Runs on the block's region. */
    private void refresh(Session s) {
        if (cancelled || s.closed) {
            return;
        }
        Inventory live = valid(s) ? live(s) : null;
        if (live == null) {
            close(s);
            return;
        }
        copy(live, s.display);
        scheduleRefresh(s);
    }

    /** Must run on the block's region: the viewer must be there too, still looking at the view and nearby. */
    private boolean valid(Session s) {
        Player viewer = s.viewer;
        if (!viewer.isOnline() || !Bukkit.isOwnedByCurrentRegion(viewer)
                || viewer.getOpenInventory().getTopInventory() != s.display) {
            return false;
        }
        Location at = viewer.getLocation();
        Location block = s.location().add(0.5, 0.5, 0.5);
        return at.getWorld() != null && at.getWorld().equals(block.getWorld())
                && at.distanceSquared(block) <= MAX_DISTANCE_SQUARED;
    }

    /** The real inventory, or {@code null} when the block is no longer the same kind of container. */
    private static @Nullable Inventory live(Session s) {
        BlockState state = s.block.getState(false);
        if (state instanceof Lidded && state instanceof Container container
                && container.getInventory().getSize() == s.liveSize) {
            return container.getInventory();
        }
        return null;
    }

    private static void copy(Inventory live, Inventory display) {
        int size = live.getSize();
        for (int i = 0; i < display.getSize(); i++) {
            ItemStack item = i < size ? live.getItem(i) : null;
            display.setItem(i, item == null || item.isEmpty() ? null : item.clone());
        }
    }

    // ---- clicks ----

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Session s)) {
            return;
        }
        Inventory clicked = event.getClickedInventory();
        boolean top = clicked != null && clicked == s.display;
        boolean bottom = clicked != null && !top;
        boolean modify = s.modify && event.getWhoClicked().hasPermission(VanishManager.PERM_SILENT_MODIFY);
        SilentClickRules.Decision decision = SilentClickRules.decide(top, bottom, modify, event.getClick(),
                event.getAction());
        if (decision == SilentClickRules.Decision.ALLOW) {
            return;
        }
        event.setCancelled(true);
        if (decision == SilentClickRules.Decision.CANCEL || cancelled || s.closed
                || event.getWhoClicked() != s.viewer) {
            return;
        }
        int slot = event.getSlot();
        if (slot < 0 || !s.busy.compareAndSet(false, true)) {
            return;
        }
        // Deferred out of the click event and onto the region that owns the container.
        TaskHandle task = ctx.scheduler().region(s.location(), () -> {
            try {
                edit(s, decision, slot);
            } catch (RuntimeException e) {
                ctx.logger().error("Silent container edit failed for {} at {}", s.viewer.getName(), s.location(), e);
                message(s.viewer, "vanish.failed");
            } finally {
                s.busy.set(false);
            }
        });
        if (task.isCancelled()) {
            s.busy.set(false);
        }
    }

    /** Runs on the block's region. Reads and writes only live inventories, with no gap between the writes. */
    private void edit(Session s, SilentClickRules.Decision decision, int slot) {
        if (cancelled || s.closed) {
            return;
        }
        Player viewer = s.viewer;
        if (!viewer.isOnline() || !Bukkit.isOwnedByCurrentRegion(viewer)) {
            message(viewer, "vanish.silent-distant");
            return;
        }
        if (!viewer.hasPermission(VanishManager.PERM_SILENT_MODIFY) || !valid(s)) {
            return;
        }
        Inventory live = live(s);
        if (live == null) {
            close(s);
            return;
        }
        var own = viewer.getInventory();
        switch (decision) {
            case SWAP_WITH_CURSOR -> {
                if (slot >= live.getSize()) {
                    return;
                }
                ItemStack cursor = viewer.getItemOnCursor();
                if (!cursor.isEmpty() && s.shulker && Tag.SHULKER_BOXES.isTagged(cursor.getType())) {
                    return;
                }
                ItemStack current = live.getItem(slot);
                live.setItem(slot, cursor.isEmpty() ? null : cursor.clone());
                viewer.setItemOnCursor(current == null || current.isEmpty() ? null : current.clone());
            }
            case TAKE_TO_PLAYER -> {
                if (slot >= live.getSize()) {
                    return;
                }
                ItemStack current = live.getItem(slot);
                if (current == null || current.isEmpty()) {
                    return;
                }
                Map<Integer, ItemStack> left = own.addItem(current.clone());
                live.setItem(slot, left.isEmpty() ? null : left.values().iterator().next());
            }
            case PUT_INTO_CONTAINER -> {
                ItemStack item = own.getItem(slot);
                if (item == null || item.isEmpty() || (s.shulker && Tag.SHULKER_BOXES.isTagged(item.getType()))) {
                    return;
                }
                Map<Integer, ItemStack> left = live.addItem(item.clone());
                own.setItem(slot, left.isEmpty() ? null : left.values().iterator().next());
            }
            default -> {
                return;
            }
        }
        copy(live, s.display);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Session s) {
            int topSize = s.display.getSize();
            if (event.getRawSlots().stream().anyMatch(raw -> raw < topSize)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Session s) {
            s.stop();
            sessions.remove(s.viewer.getUniqueId(), s);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Session s = sessions.remove(event.getPlayer().getUniqueId());
        if (s != null) {
            s.stop();
        }
    }

    private void message(Player player, String key) {
        ctx.scheduler().entity(player, () -> ctx.messages().send(player, key), null);
    }

    private void close(Session s) {
        s.stop();
        sessions.remove(s.viewer.getUniqueId(), s);
        ctx.scheduler().entity(s.viewer, () -> {
            if (s.viewer.getOpenInventory().getTopInventory() == s.display) {
                s.viewer.closeInventory();
            }
        }, null);
    }

    @Override
    public void cancel() {
        cancelled = true;
        for (Session s : sessions.values()) {
            try {
                close(s);
            } catch (RuntimeException e) {
                s.stop(); // plugin disabling: the inventory is closed by the disconnect anyway
            }
        }
        sessions.clear();
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }
}
