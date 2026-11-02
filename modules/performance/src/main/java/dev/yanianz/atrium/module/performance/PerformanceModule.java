package dev.yanianz.atrium.module.performance;

import dev.yanianz.atrium.api.serverlist.ServerListService;
import dev.yanianz.atrium.api.vanish.VanishService;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.permissions.PermissionDefault;

/**
 * Server health and cleanup tools:
 * <ul>
 *   <li>{@code /lag}: one-shot Paper/Folia TPS, MSPT, memory and world counts.</li>
 *   <li>{@code /groundclean}: region-scoped removal of old drops with an optional countdown schedule.</li>
 *   <li>Optional per-chunk caps for animals, monsters, items and minecarts.</li>
 *   <li>The server list: MOTD override, online/max numbers and a vanished-free player sample.</li>
 *   <li>{@code /viewdistance}, {@code /maxplayers}, the release check and the optional console filter.</li>
 * </ul>
 */
public final class PerformanceModule implements AtriumModule {
    private static final String PERM_UPDATES_NOTIFY = UpdateChecker.PERM_NOTIFY;

    private ModuleContext ctx;
    private ConfigHandle<PerformanceConfig> config;
    private GroundCleanManager groundClean;
    private EntityLimitListener entityLimits;
    private ServerMOTD motd;
    private ServerListListener serverList;
    private UpdateChecker updateChecker;
    private PluginErrorLog errorLog;
    private ConsoleFilterInstaller consoleFilter;
    private volatile LogRules logRules = LogRules.disabled();
    private TaskHandle schedule;
    private volatile boolean stopped;

    @Override
    public String id() {
        return "performance";
    }

    @Override
    public String description() {
        return "Lag diagnostics, ground cleanup, entity limits, server list and view distance";
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

        this.motd = new ServerMOTD(ctx.dataDirectory().resolve("motd.yml"), ctx.scheduler(), ctx.logger());
        VanishService vanish = ctx.services().get(VanishService.class).orElse(null);
        this.serverList = new ServerListListener(ctx, config::get, motd, vanish);
        ctx.listen(serverList);
        ctx.service(ServerListService.class, new ServerListListener.Service(motd, serverList));

        this.updateChecker = new UpdateChecker(ctx, config::get);
        ctx.listen(updateChecker);
        updateChecker.start();

        this.errorLog = new PluginErrorLog(config.get().consoleLog.recentSize());
        this.logRules = buildRules();
        this.consoleFilter = new ConsoleFilterInstaller(() -> logRules, errorLog, ctx.logger());
        if (logRules.enabled() && !consoleFilter.install()) {
            ctx.logger().warn("console-log is enabled but the filter is inactive");
        }

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
        ctx.permission("atrium.maxplayers", PermissionDefault.OP,
                "Change the player cap the server list reports");
        ctx.permission("atrium.viewdistance", PermissionDefault.TRUE,
                "Change your own view distance");
        ctx.permission("atrium.viewdistance.others", PermissionDefault.OP,
                "Change another player's view distance");
        ctx.permission("atrium.viewdistance.world", PermissionDefault.OP,
                "Change the view distance of a world");
        ctx.permission("atrium.viewdistance.global", PermissionDefault.OP,
                "Change the view distance of every loaded world");
        ctx.permission("atrium.updates", PermissionDefault.OP,
                "Show and re-run the release check");
        ctx.permission(PERM_UPDATES_NOTIFY, PermissionDefault.OP,
                "Receive the newer-release notice when joining");
        ctx.permission("atrium.consolefilter", PermissionDefault.OP,
                "Inspect the console filter and the recent problems");

        Supplier<LogRules> rules = () -> logRules;
        new PerformanceCommands(ctx, config, groundClean, lag, store).register();
        new ServerCommands(ctx, config, serverList, updateChecker, errorLog, rules).register();
        startSchedule();
    }

    private LogRules buildRules() {
        return LogRules.build(config.get().consoleLog,
                (key, problem) -> ctx.logger().warn("console-log {} ignored: {}", key, problem));
    }

    @Override
    public void reload() {
        if (groundClean != null) {
            groundClean.reload();
        }
        // The recent list keeps its entries; only the rules change on a reload.
        logRules = buildRules();
        if (consoleFilter != null) {
            if (logRules.enabled()) {
                consoleFilter.install();
            } else {
                consoleFilter.uninstall();
            }
        }
        if (updateChecker != null) {
            updateChecker.stop();
            updateChecker.start();
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
        if (updateChecker != null) {
            updateChecker.stop();
        }
        if (consoleFilter != null) {
            consoleFilter.uninstall();
            consoleFilter = null;
        }
        if (groundClean != null) {
            groundClean.shutdown();
        }
    }
}
