package dev.yanianz.atrium.module.inventories;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.ModuleContext;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Automatic snapshots. Both events run on the player's own region thread, so the inventory can be read
 * directly; only the insert is off-thread. A failure is logged and never breaks the death or quit.
 */
final class SnapshotCapture implements Listener {
    private final ModuleContext ctx;
    private final ConfigHandle<InventoriesConfig> config;
    private final SnapshotStore store;
    private volatile boolean closed;

    SnapshotCapture(ModuleContext ctx, ConfigHandle<InventoriesConfig> config, SnapshotStore store) {
        this.ctx = ctx;
        this.config = config;
        this.store = store;
    }

    /** LOWEST: capture the inventory as it was before other death plugins (graves, keep-inventory) touch it. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        if (!config.get().captureOnDeath) {
            return;
        }
        capture(event.getEntity(), SnapshotReason.DEATH, "death");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (!config.get().captureOnQuit) {
            return;
        }
        capture(event.getPlayer(), SnapshotReason.QUIT, "quit");
    }

    private void capture(Player player, SnapshotReason reason, String what) {
        if (closed) {
            return;
        }
        try {
            ItemStackSnapshots.Captured captured = ItemStackSnapshots.capture(player);
            InventoriesConfig current = config.get();
            SnapshotStore.Draft draft = new SnapshotStore.Draft(player.getUniqueId(), player.getName(),
                    SnapshotCodec.FORMAT, System.currentTimeMillis(), null, "System", null, reason,
                    captured.stacks(), captured.bytes());
            store.insert(draft)
                    .thenCompose(id -> store.prune(player.getUniqueId(),
                            InventoriesConfig.retentionCount(current),
                            InventoriesConfig.cutoff(current, System.currentTimeMillis())))
                    .whenComplete((removed, error) -> {
                        if (error != null) {
                            ctx.logger().warn("Saving the {} snapshot of {} failed", what, player.getUniqueId(),
                                    error);
                        }
                    });
        } catch (RuntimeException e) {
            ctx.logger().warn("Capturing the {} snapshot of {} failed", what, player.getUniqueId(), e);
        }
    }

    void shutdown() {
        closed = true;
    }
}
