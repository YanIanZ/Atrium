package dev.yanianz.atrium.module.combat;

import dev.yanianz.atrium.api.combat.CombatService;
import dev.yanianz.atrium.api.player.FlightService;
import dev.yanianz.atrium.api.vanish.VanishService;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Holds the combat tags in memory and drives their display. Every method that touches a player runs on
 * that player's own thread (Folia); the map itself is safe from any thread.
 */
final class CombatTracker implements CombatService {
    /** Permission that keeps a player out of combat entirely. */
    static final String BYPASS_PERMISSION = "atrium.combat.bypass";

    private record Tagged(long untilMillis, long totalMillis) {}

    private final ModuleContext ctx;
    private final ConfigHandle<CombatConfig> config;
    private final @Nullable FlightService flight;
    private final @Nullable VanishService vanish;
    private final Map<UUID, Tagged> tagged = new ConcurrentHashMap<>();
    private final Map<UUID, TaskHandle> timers = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();

    CombatTracker(ModuleContext ctx, ConfigHandle<CombatConfig> config, @Nullable FlightService flight,
            @Nullable VanishService vanish) {
        this.ctx = ctx;
        this.config = config;
        this.flight = flight;
        this.vanish = vanish;
    }

    // ---- CombatService ----

    @Override
    public boolean isTagged(UUID player) {
        return remainingMillis(player) > 0;
    }

    @Override
    public long remainingMillis(UUID player) {
        Tagged entry = tagged.get(player);
        return entry == null ? 0L : CombatTiming.remaining(System.currentTimeMillis(), entry.untilMillis());
    }

    @Override
    public void tag(Player player, Duration duration) {
        if (player == null || duration == null || duration.toMillis() <= 0) {
            return;
        }
        long millis = duration.toMillis();
        ctx.scheduler().entity(player, () -> tagNow(player, millis), null);
    }

    @Override
    public void clear(UUID player) {
        forget(player);
    }

    // ---- tag lifetime ----

    /** Whether the tag rules allow tagging this player right now. Player's thread only. */
    boolean canTag(Player player) {
        CombatConfig.Tag tag = config.get().tag;
        if (!tag.enabled) {
            return false;
        }
        if (!config.get().worldEnabled(player.getWorld().getName())) {
            return false;
        }
        if (player.hasPermission(BYPASS_PERMISSION)) {
            return false;
        }
        return vanish == null || !vanish.isVanished(player.getUniqueId());
    }

    /** Applies (or extends) a tag. Player's thread only. */
    void tagNow(Player player, long durationMillis) {
        if (durationMillis <= 0 || !canTag(player)) {
            return;
        }
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Tagged previous = tagged.get(id);
        boolean fresh = previous == null || previous.untilMillis() <= now;
        long until = CombatTiming.extendedUntil(previous == null ? 0L : previous.untilMillis(), now, durationMillis);
        long total = Math.max(durationMillis, previous == null ? 0L : previous.totalMillis());
        tagged.put(id, new Tagged(until, total));
        if (fresh && config.get().tag.notify) {
            ctx.messages().send(player, "combat.tag-start", Msg.number("seconds", CombatTiming.secondsLeft(until - now)));
        }
        suppressFlight(player);
        startTimer(player);
        updateDisplay(player, tagged.get(id));
    }

    /** Removes the tag and tells the player. Player's thread only. Returns whether a tag was running. */
    boolean clearNow(Player player) {
        UUID id = player.getUniqueId();
        boolean wasTagged = tagged.remove(id) != null;
        stopTimer(id);
        hideBar(player);
        if (wasTagged && config.get().tag.notify) {
            ctx.messages().send(player, "combat.tag-end");
        }
        return wasTagged;
    }

    /** Removes the tag without a message and hides the bar on the player's thread. Safe from any thread. */
    void forget(UUID id) {
        Tagged removed = tagged.remove(id);
        stopTimer(id);
        Player player = Bukkit.getPlayer(id);
        if (player != null) {
            ctx.scheduler().entity(player, () -> hideBar(player), null);
        } else if (removed != null) {
            bars.remove(id);
        }
    }

    /** Removes the tag without scheduling anything; used while the player is quitting. */
    void forgetNow(UUID id) {
        tagged.remove(id);
        stopTimer(id);
        bars.remove(id);
    }

    /** Cancels everything; the module context cancels the tracked tasks as well. */
    void shutdown() {
        for (Map.Entry<UUID, BossBar> entry : Map.copyOf(bars).entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                BossBar bar = entry.getValue();
                ctx.scheduler().entity(player, () -> player.hideBossBar(bar), null);
            }
        }
        for (TaskHandle handle : timers.values()) {
            handle.cancel();
        }
        tagged.clear();
        timers.clear();
        bars.clear();
    }

    /** Re-applies the rules after a reload: a disabled timer or a disabled world drops running tags. */
    void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (!tagged.containsKey(id)) {
                continue;
            }
            ctx.scheduler().entity(player, () -> {
                if (!canTag(player)) {
                    clearNow(player);
                } else {
                    updateDisplay(player, tagged.get(id));
                }
            }, null);
        }
    }

    // ---- tag timer ----

    private void startTimer(Player player) {
        UUID id = player.getUniqueId();
        TaskHandle existing = timers.remove(id);
        if (existing != null) {
            existing.cancel();
        }
        TaskHandle handle = ctx.scheduler().entityTimer(player, () -> tick(player), () -> forgetNow(id), 1L, 20L);
        timers.put(id, ctx.track(handle));
    }

    private void stopTimer(UUID id) {
        TaskHandle handle = timers.remove(id);
        if (handle != null) {
            handle.cancel();
        }
    }

    private void tick(Player player) {
        if (!player.isOnline()) {
            forgetNow(player.getUniqueId());
            return;
        }
        UUID id = player.getUniqueId();
        Tagged entry = tagged.get(id);
        if (entry == null) {
            stopTimer(id);
            hideBar(player);
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= entry.untilMillis()) {
            clearNow(player);
            return;
        }
        if (config.get().tag.blockFlight) {
            suppressFlight(player);
        }
        if (config.get().tag.blockElytra && player.isGliding()) {
            player.setGliding(false);
        }
        updateDisplay(player, entry);
    }

    /**
     * Drops every flight claim of the player and stops the current flight. Claims are what grants flight in
     * Atrium; releasing them never turns off flight another owner still needs and needs an explicit /fly
     * again once combat ends. Player's thread only.
     */
    private void suppressFlight(Player player) {
        if (!config.get().tag.blockFlight) {
            return;
        }
        if (flight != null) {
            for (String owner : flight.claims(player)) {
                flight.release(player, owner);
            }
        }
        if (player.isFlying()) {
            player.setFlying(false);
        }
    }

    // ---- display ----

    private void updateDisplay(Player player, @Nullable Tagged entry) {
        if (entry == null) {
            hideBar(player);
            return;
        }
        CombatRules.Display display = CombatRules.display(config.get().tag.display);
        if (display == CombatRules.Display.NONE) {
            hideBar(player);
            return;
        }
        long remaining = CombatTiming.remaining(System.currentTimeMillis(), entry.untilMillis());
        int seconds = CombatTiming.secondsLeft(remaining);
        if (display == CombatRules.Display.ACTIONBAR) {
            hideBar(player);
            ctx.messages().actionBar(player, "combat.tag-actionbar", Msg.number("seconds", seconds));
            return;
        }
        UUID id = player.getUniqueId();
        float progress = CombatTiming.progress(remaining, entry.totalMillis());
        Component title = ctx.messages().render(player, "combat.tag-bossbar", Msg.number("seconds", seconds));
        BossBar bar = bars.get(id);
        if (bar == null) {
            bar = BossBar.bossBar(title, progress, config.get().tag.resolvedBossBarColor(), BossBar.Overlay.PROGRESS);
            bars.put(id, bar);
            player.showBossBar(bar);
            return;
        }
        bar.name(title);
        bar.progress(progress);
        bar.color(config.get().tag.resolvedBossBarColor());
    }

    private void hideBar(Player player) {
        BossBar bar = bars.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }
}
