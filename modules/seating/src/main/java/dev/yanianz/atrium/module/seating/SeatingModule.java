package dev.yanianz.atrium.module.seating;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.util.ArrayList;
import org.bukkit.permissions.PermissionDefault;

/**
 * Sitting, laying and crawling.
 *
 * <p>Seats are invisible non-persistent marker armor stands with a persistent-data marker. Every
 * exit path (dismount, quit, death, teleport, world change, block break, chunk load and module
 * disable) removes both the entity and the bookkeeping. Crawling is purely client-side: a barrier
 * block is sent to one client and restored when the crawl ends. Neither feature runs a per-tick
 * task; the only timer is a slow consistency check that returns immediately when nobody is seated.
 */
public final class SeatingModule implements AtriumModule {
    static final String PERM_SIT = "atrium.sit";
    static final String PERM_SIT_OTHERS = "atrium.sit.others";
    static final String PERM_LAY = "atrium.lay";
    static final String PERM_LAY_OTHERS = "atrium.lay.others";
    static final String PERM_CRAWL = "atrium.crawl";
    static final String PERM_CRAWL_OTHERS = "atrium.crawl.others";
    static final String PERM_INTERACT = "atrium.seating.interact";
    static final String PERM_WORLD_BYPASS = "atrium.seating.worlds.bypass";
    static final String PERM_ADMIN = "atrium.seating.admin";
    static final String PERM_SWEEP = "atrium.seating.sweep";

    private ModuleContext ctx;
    private ConfigHandle<SeatingConfig> config;
    private SeatManager seats;
    private CrawlManager crawl;
    private TaskHandle checkTask = TaskHandle.NOOP;

    @Override
    public String id() {
        return "seating";
    }

    @Override
    public String description() {
        return "Sit, lay and crawl; click stairs and slabs to sit down";
    }

    @Override
    public void enable(ModuleContext context) throws Exception {
        this.ctx = context;
        this.config = context.config(SeatingConfig.class, SeatingConfig::new);
        validate(config.get());
        this.seats = new SeatManager(ctx, config);
        this.crawl = new CrawlManager(ctx, config);
        this.seats.beforeSeat(player -> crawl.stop(player, CrawlRules.StopReason.INVALID));
        ctx.listen(new SeatingListener(ctx, config, seats, crawl));
        registerPermissions();
        new SeatingCommands(ctx, seats, crawl).register();
        scheduleCheck();
    }

    private void registerPermissions() {
        ctx.permission(PERM_SIT, PermissionDefault.TRUE, "Sit down with /sit");
        ctx.permission(PERM_SIT_OTHERS, PermissionDefault.OP, "Make another player sit with /sit <player>");
        ctx.permission(PERM_LAY, PermissionDefault.TRUE, "Lay down with /lay");
        ctx.permission(PERM_LAY_OTHERS, PermissionDefault.OP, "Make another player lay down with /lay <player>");
        ctx.permission(PERM_CRAWL, PermissionDefault.TRUE, "Crawl with /crawl");
        ctx.permission(PERM_CRAWL_OTHERS, PermissionDefault.OP, "Make another player crawl with /crawl <player>");
        ctx.permission(PERM_INTERACT, PermissionDefault.TRUE, "Sit on stairs and slabs by right-clicking them");
        ctx.permission(PERM_WORLD_BYPASS, PermissionDefault.OP,
                "Use seating features in worlds disabled in the seating config");
        ctx.permission(PERM_ADMIN, PermissionDefault.OP, "Use /seating help and status commands");
        ctx.permission(PERM_SWEEP, PermissionDefault.OP, "Remove stale seat records with /seating sweep");
    }

    private void scheduleCheck() {
        checkTask.cancel();
        int seconds = config.get().seat.checkIntervalSeconds;
        if (seconds <= 0) {
            checkTask = TaskHandle.NOOP;
            return;
        }
        long ticks = seconds * 20L;
        checkTask = ctx.track(ctx.scheduler().globalTimer(() -> {
            try {
                seats.checkAll();
            } catch (RuntimeException e) {
                ctx.logger().error("Seat consistency check failed", e);
            }
        }, ticks, ticks));
    }

    @Override
    public void reload() throws Exception {
        validate(config.get());
        scheduleCheck();
    }

    @Override
    public void disable() {
        checkTask.cancel();
        if (crawl != null) {
            crawl.shutdown();
        }
        if (seats != null) {
            seats.shutdown();
        }
    }

    private static void validate(SeatingConfig cfg) {
        if (cfg.disabledWorlds == null) {
            cfg.disabledWorlds = new ArrayList<>();
        }
        if (cfg.interaction == null) {
            cfg.interaction = new SeatingConfig.Interaction();
        }
        if (cfg.seat == null) {
            cfg.seat = new SeatingConfig.Seat();
        }
        if (cfg.crawlOptions == null) {
            cfg.crawlOptions = new SeatingConfig.CrawlOptions();
        }
        cfg.seat.heightOffset = Math.clamp(cfg.seat.heightOffset, -2.0, 2.0);
        cfg.seat.layDepth = Math.clamp(cfg.seat.layDepth, 0.0, 1.0);
        cfg.seat.checkIntervalSeconds = Math.clamp(cfg.seat.checkIntervalSeconds, 0, 3600);
    }
}
