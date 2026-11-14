package dev.yanianz.atrium.module.webstore;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.time.Duration;
import org.bukkit.permissions.PermissionDefault;

/**
 * Delivers webstore orders. The website INSERTs rows into {@code {prefix}webstore_deliveries}; a tracked timer
 * claims pending rows (safe with several servers on one database, see {@link DeliveryStore}), runs the package's
 * console commands and tells the recipient (and a gifting buyer) when they are online.
 */
public final class WebstoreModule implements AtriumModule {
    private ModuleContext ctx;
    private ConfigHandle<WebstoreConfig> config;
    private DeliveryWorker worker;
    private TaskHandle timer = TaskHandle.NOOP;

    /**
     * Opt-in: only ONE server of the network should deliver web orders, so a new install writes
     * {@code webstore: false} to modules.yml and the operator turns it on where deliveries run.
     */
    @Override
    public boolean enabledByDefault() {
        return false;
    }

    @Override
    public String id() {
        return "webstore";
    }

    @Override
    public String description() {
        return "Webstore deliveries: runs the commands of paid or gifted store packages";
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.config = ctx.config(WebstoreConfig.class, WebstoreConfig::new);
        ctx.migrate(DeliveryStore.MIGRATIONS);
        DeliveryStore store = new DeliveryStore(ctx.database());
        this.worker = new DeliveryWorker(ctx, config, store);

        ctx.permission(WebstoreCommands.ADMIN_PERMISSION, PermissionDefault.OP,
                "Show the webstore delivery queue and retry failed orders");
        new WebstoreCommands(ctx, store).register();
        startTimer();
    }

    private synchronized void startTimer() {
        timer.cancel();
        Duration period = Duration.ofSeconds(config.get().pollSeconds());
        timer = ctx.track(ctx.scheduler().asyncTimer(worker::tick, Duration.ofSeconds(5), period));
    }

    @Override
    public void reload() {
        if (worker != null) {
            startTimer();
        }
    }

    @Override
    public synchronized void disable() {
        timer.cancel();
        timer = TaskHandle.NOOP;
        if (worker != null) {
            worker.stop();
            worker = null;
        }
    }
}
