package dev.yanianz.atrium.module.performance;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.permissions.PermissionDefault;

/**
 * Server health and cleanup tools:
 * <ul>
 *   <li>{@code /lag}: one-shot Paper/Folia TPS, MSPT, memory and world counts.</li>
 *   <li>{@code /groundclean}: region-scoped removal of old drops with an optional countdown schedule.</li>
 *   <li>Optional per-chunk caps for animals, monsters, items and minecarts.</li>
 * </ul>
 */
public final class PerformanceModule implements AtriumModule {
    private ModuleContext ctx;
    private ConfigHandle<PerformanceConfig> config;
    private GroundCleanManager groundClean;
    private EntityLimitListener entityLimits;
    private TaskHandle schedule;
    private volatile boolean stopped;

    @Override
    public String id() {
        return "performance";
    }

    @Override
    public String description() {
        return "Lag diagnostics, ground cleanup and per-chunk entity limits";
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.config = ctx.config(PerformanceConfig.class, PerformanceConfig::new);
        ctx.migrate(GroundCleanStore.MIGRATIONS);
        GroundCleanStore store = new GroundCleanStore(ctx.database());

        this.groundClean = new GroundCleanManager(ctx, config::get, store);
        this.entityLimits = new EntityLimitListener(ctx, config::get);
        ctx.listen(entityLimits);
        LagReporter lag = new LagReporter(ctx, config::get, entityLimits);

        ctx.permission("atrium.lag", PermissionDefault.OP,
                "Show TPS, tick time, memory and loaded entity counts");
        ctx.permission("atrium.groundclean", PermissionDefault.OP,
                "Remove old dropped items and XP orbs");
        ctx.permission("atrium.groundclean.silent", PermissionDefault.OP,
                "Run /groundclean without countdown broadcasts");
        ctx.permission("atrium.groundclean.history", PermissionDefault.OP,
                "Show the recent /groundclean history");
        ctx.permission("atrium.groundclean.protect", PermissionDefault.TRUE,
                "Keep dropped items within a radius of you during ground cleanup");

        new PerformanceCommands(ctx, config, groundClean, lag, store).register();
        startSchedule();
    }

    @Override
    public void reload() {
        if (groundClean != null) {
            groundClean.reload();
        }
        startSchedule();
    }

    private void startSchedule() {
        if (schedule != null) {
            schedule.cancel();
            schedule = null;
        }
        if (stopped || ctx == null) {
            return;
        }
        PerformanceConfig.GroundClean cfg = config.get().groundClean;
        if (!cfg.scheduleEnabled) {
            return;
        }
        long period = cfg.scheduleIntervalMinutes() * 60L * 20L;
        schedule = ctx.track(ctx.scheduler().globalTimer(this::scheduledRun, period, period));
        ctx.logger().info("Ground cleanup scheduled every {} minute(s){}", cfg.scheduleIntervalMinutes(),
                cfg.scheduleWorld.isBlank() ? " in every world" : " in " + cfg.scheduleWorld);
    }

    private void scheduledRun() {
        if (stopped) {
            return;
        }
        PerformanceConfig.GroundClean cfg = config.get().groundClean;
        if (!cfg.scheduleEnabled || groundClean.isActive()) {
            return;
        }
        String world = cfg.scheduleWorld.isBlank() ? null : cfg.scheduleWorld;
        GroundCleanManager.StartResult result = groundClean.start(Bukkit.getConsoleSender(), world, false,
                cfg.scheduledCountdownSeconds(), GroundCleanManager.Trigger.SCHEDULE);
        switch (result) {
            case STARTED -> {
                // The manager logs the summary when the run finishes.
            }
            case BUSY -> ctx.logger().info("Scheduled ground cleanup skipped: another run is active");
            case NO_PLAYERS -> ctx.logger().info("Scheduled ground cleanup skipped: no player is online");
            case UNKNOWN_WORLD -> ctx.logger().warn(
                    "Scheduled ground cleanup skipped: world '{}' does not exist", world);
        }
    }

    @Override
    public void disable() {
        stopped = true;
        if (schedule != null) {
            schedule.cancel();
            schedule = null;
        }
        if (groundClean != null) {
            groundClean.shutdown();
        }
    }
}
