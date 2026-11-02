package dev.yanianz.atrium.module.worldrules;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.World;

/**
 * Applies the per-world day/night multipliers on one global timer. World time is world state, so the
 * adjustment runs on the global region thread and never reads an entity; a world without a multiplier is
 * skipped, and the timer is not started at all while the feature is disabled.
 */
final class TimeRateManager {
    private final ModuleContext ctx;
    private final ConfigHandle<WorldRulesConfig> config;
    private volatile TaskHandle task;

    TimeRateManager(ModuleContext ctx, ConfigHandle<WorldRulesConfig> config) {
        this.ctx = ctx;
        this.config = config;
    }

    /** Starts (or restarts) the timer; does nothing when every loaded world uses vanilla speed. */
    void recompile() {
        stop();
        WorldRulesConfig.TimeRate settings = config.get().timeRate;
        if (!settings.enabled) {
            return;
        }
        int interval = settings.intervalTicks();
        boolean active = false;
        if (config.get().worlds != null) {
            for (WorldRule rule : config.get().worlds.values()) {
                if (rule != null && (rule.dayTimeMultiplier() != 1.0 || rule.nightTimeMultiplier() != 1.0)) {
                    active = true;
                    break;
                }
            }
        }
        if (!active) {
            return;
        }
        task = ctx.track(ctx.scheduler().globalTimer(() -> tick(interval), interval, interval));
    }

    private void tick(int interval) {
        for (World world : Bukkit.getWorlds()) {
            WorldRule rule = config.get().rule(world);
            double multiplier = SleepMath.isNight(world.getTime())
                    ? rule.nightTimeMultiplier() : rule.dayTimeMultiplier();
            if (multiplier == 1.0) {
                continue;
            }
            if (!Boolean.TRUE.equals(world.getGameRuleValue(GameRules.ADVANCE_TIME))) {
                continue;
            }
            long extra = TimeRateMath.extraTicks(interval, multiplier);
            if (extra == 0) {
                continue;
            }
            world.setTime(TimeRateMath.advance(world.getTime(), extra));
        }
    }

    void stop() {
        TaskHandle handle = task;
        task = null;
        if (handle != null) {
            handle.cancel();
        }
    }
}
