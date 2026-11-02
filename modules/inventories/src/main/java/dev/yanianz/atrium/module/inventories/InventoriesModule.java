package dev.yanianz.atrium.module.inventories;

import dev.yanianz.atrium.api.inventories.InventorySnapshotService;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.permissions.PermissionDefault;

/**
 * Saved inventory snapshots: automatic capture on death and quit, a read-only preview, and a confirmed
 * restore that always keeps an undo snapshot. Everything is Folia-safe: players are only touched on their own
 * thread and storage stays on the I/O executor.
 */
public final class InventoriesModule implements AtriumModule {
    private static final Duration PURGE_INTERVAL = Duration.ofHours(6);

    private ModuleContext ctx;
    private ConfigHandle<InventoriesConfig> config;
    private SnapshotStore store;
    private SnapshotPreview preview;
    private SnapshotCapture capture;
    private PendingEditStore pendingStore;
    private PendingEditApplier applier;
    private PendingReturnStore returnStore;
    private PendingReturnApplier returnApplier;

    @Override
    public String id() {
        return "inventories";
    }

    @Override
    public String description() {
        return "Saved inventory snapshots: capture, preview, load, remove, clear the ender chest or everything";
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.config = ctx.config(InventoriesConfig.class, InventoriesConfig::new);
        List<dev.yanianz.atrium.core.storage.Migration> migrations = new ArrayList<>(SnapshotStore.MIGRATIONS);
        migrations.addAll(PendingEditStore.MIGRATIONS);
        migrations.addAll(PendingReturnStore.MIGRATIONS);
        ctx.migrate(migrations);
        this.store = new SnapshotStore(ctx.database());
        this.pendingStore = new PendingEditStore(ctx.database());
        this.returnStore = new PendingReturnStore(ctx.database());
        this.preview = new SnapshotPreview(ctx);
        this.capture = new SnapshotCapture(ctx, config, store);
        ctx.listen(capture);
        this.applier = new PendingEditApplier(ctx, config, store, pendingStore, returnStore);
        ctx.listen(applier);
        ctx.track(applier);
        this.returnApplier = new PendingReturnApplier(ctx, returnStore);
        ctx.listen(returnApplier);
        ctx.track(returnApplier);
        ctx.permission("atrium.inventories.notify", PermissionDefault.OP,
                "Receive a message when a pending offline inventory edit is discarded");
        ctx.service(InventorySnapshotService.class, new SnapshotService(ctx, store, pendingStore, applier));
        // Reloads edits that survived a restart before anyone can join.
        applier.preload();
        new InventoryCommands(ctx, config, store, preview).register();
        new ClearAllCommand(ctx, config, store).register();
        // Age retention is applied now and periodically; no per-tick work when nobody uses the module.
        ctx.track(ctx.scheduler().asyncTimer(this::purgeExpired, PURGE_INTERVAL, PURGE_INTERVAL));
        purgeExpired();
    }

    private void purgeExpired() {
        InventoriesConfig current = config.get();
        long now = System.currentTimeMillis();
        long cutoff = InventoriesConfig.cutoff(current, now);
        if (cutoff > 0) {
            store.purgeOlderThan(cutoff).whenComplete((removed, error) -> {
                if (error != null) {
                    ctx.logger().warn("Purging expired inventory snapshots failed", error);
                } else if (removed != null && removed > 0) {
                    ctx.logger().info("Purged {} expired inventory snapshots", removed);
                }
            });
        }
        long pendingCutoff = InventoriesConfig.pendingCutoff(current, now);
        if (pendingCutoff > 0) {
            pendingStore.purgeOlderThan(pendingCutoff).whenComplete((removed, error) -> {
                if (error != null) {
                    ctx.logger().warn("Purging expired offline edits failed", error);
                } else if (removed != null && removed > 0) {
                    ctx.logger().info("Purged {} expired offline edits", removed);
                }
            });
        }
    }

    @Override
    public void disable() {
        if (capture != null) {
            capture.shutdown();
        }
    }
}
