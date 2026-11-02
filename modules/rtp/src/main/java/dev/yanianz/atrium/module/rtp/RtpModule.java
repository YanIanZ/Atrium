package dev.yanianz.atrium.module.rtp;

import dev.yanianz.atrium.api.rtp.RandomTeleportService;
import dev.yanianz.atrium.api.teleport.ExtendedTeleportService;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.bukkit.permissions.PermissionDefault;

/** Random teleport: async, border-correct search with a ready-location pool; publishes {@link RandomTeleportService}. */
public final class RtpModule implements AtriumModule {
    private RtpSearcher searcher;

    @Override
    public String id() {
        return "rtp";
    }

    @Override
    public String description() {
        return "Random teleport: /rtp [world] [player], first-join and respawn RTP";
    }

    @Override
    public Set<String> dependencies() {
        return Set.of("teleport");
    }

    @Override
    public Set<String> softDependencies() {
        return Set.of("economy");
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        ConfigHandle<RtpConfig> config = ctx.config(RtpConfig.class, RtpConfig::new);
        ctx.migrate(RtpStore.MIGRATIONS);
        RtpStore store = new RtpStore(ctx.database());
        ExtendedTeleportService teleport = ctx.services().require(ExtendedTeleportService.class);

        RtpCooldowns cooldowns = new RtpCooldowns(Clock.systemUTC());
        for (RtpStore.CooldownRow row : store.loadCooldowns(System.currentTimeMillis()).get(30, TimeUnit.SECONDS)) {
            cooldowns.restore(row.player(), row.world(), Instant.ofEpochMilli(row.expiresAt()));
        }

        searcher = new RtpSearcher(ctx, config);
        RtpCommands commands = new RtpCommands(ctx, config, searcher, cooldowns, store, teleport);
        commands.register();
        ctx.listen(commands);

        ctx.permission(RtpCommands.PERM_RTP, PermissionDefault.TRUE, "Random teleport with /rtp");
        ctx.permission(RtpCommands.PERM_OTHERS, PermissionDefault.OP, "Random teleport other players (free, no warmup)");
        ctx.permission(RtpCommands.PERM_COOLDOWN_BYPASS, PermissionDefault.OP, "Ignore the /rtp cooldown");
        ctx.permission(RtpCommands.PERM_COST_BYPASS, PermissionDefault.OP, "Random teleport for free");
        // atrium.rtp.world.<world> is deliberately not registered: registering would give it a default value,
        // and an unset node means "allowed unless require-world-permission" (see RtpCommands#worldAllowed).

        RtpConfig.Pool pool = config.get().pool;
        ctx.track(ctx.scheduler().asyncTimer(() -> {
            try {
                searcher.refillTick();
            } catch (RuntimeException e) {
                ctx.logger().error("RTP pool refill failed", e);
            }
        }, Duration.ofSeconds(Math.max(1, pool.initialDelaySeconds)), Duration.ofSeconds(Math.max(1, pool.refillIntervalSeconds))));
        ctx.track(ctx.scheduler().asyncTimer(() -> {
            cooldowns.purge();
            store.purge(System.currentTimeMillis()).exceptionally(error -> {
                ctx.logger().warn("Purging expired RTP cooldowns failed", error);
                return 0;
            });
        }, Duration.ofMinutes(1), Duration.ofMinutes(10)));

        ctx.service(RandomTeleportService.class, commands);
    }

    @Override
    public void reload() {
        if (searcher != null) {
            searcher.reset();
        }
    }

    @Override
    public void disable() {
        if (searcher != null) {
            searcher.stop();
            searcher = null;
        }
    }
}
