package dev.yanianz.atrium.module.moderation;

import dev.yanianz.atrium.api.moderation.Punishment;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Sentence boss bar: shown to every online prisoner with the time left, refreshed on a global timer.
 * The bar is created and updated on the player's own thread; nothing here touches another entity's state.
 */
final class JailBossbar {
    private final ModuleContext ctx;
    private final ConfigHandle<ModerationConfig> config;
    private final PunishmentManager manager;
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();
    private volatile boolean running;

    JailBossbar(ModuleContext ctx, ConfigHandle<ModerationConfig> config, PunishmentManager manager) {
        this.ctx = ctx;
        this.config = config;
        this.manager = manager;
    }

    void enable() {
        ModerationConfig.Jail jail = config.get().jail;
        if (!jail.bossbar.enabled) {
            return;
        }
        running = true;
        long seconds = Math.clamp(jail.bossbar.updateSeconds, 1, 60);
        ctx.track(ctx.scheduler().globalTimer(this::tick, 20L, seconds * 20L));
    }

    void disable() {
        running = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            hide(player);
        }
        bars.clear();
    }

    /** Called when a player quits or is released, from any thread. */
    void hide(Player player) {
        BossBar bar = bars.remove(player.getUniqueId());
        if (bar == null) {
            return;
        }
        try {
            ctx.scheduler().entity(player, () -> player.hideBossBar(bar), null);
        } catch (RuntimeException e) {
            ctx.logger().debug("Could not remove the jail boss bar of {} (server shutting down?)", player.getName(), e);
        }
    }

    private void tick() {
        if (!running) {
            return;
        }
        Instant now = Instant.now();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Optional<Punishment> jail = manager.cache().active(player.getUniqueId(), PunishmentManager.JAIL, now);
            if (jail.isEmpty()) {
                hide(player);
                continue;
            }
            Punishment sentence = jail.get();
            ctx.scheduler().entity(player, () -> show(player, sentence, now), null);
        }
    }

    private void show(Player player, Punishment sentence, Instant now) {
        if (!running || !player.isOnline()) {
            return;
        }
        ModerationConfig.Jail.Bossbar settings = config.get().jail.bossbar;
        Component name = textFor(player, sentence, now);
        BossBar bar = bars.computeIfAbsent(player.getUniqueId(), id -> BossBar.bossBar(name,
                progress(sentence, now), color(settings.color), BossBar.Overlay.PROGRESS));
        bar.name(name);
        bar.progress(progress(sentence, now));
        bar.color(color(settings.color));
        player.showBossBar(bar);
    }

    /** Share of the sentence still to serve; a permanent sentence shows a full bar. */
    static float progress(Punishment sentence, Instant now) {
        if (sentence.expires() == null) {
            return 1.0f;
        }
        Duration total = Duration.between(sentence.created(), sentence.expires());
        Duration left = Duration.between(now, sentence.expires());
        if (total.isZero() || total.isNegative()) {
            return 0.0f;
        }
        double share = (double) left.toMillis() / (double) total.toMillis();
        return (float) Math.clamp(share, 0.0, 1.0);
    }

    private static BossBar.Color color(String raw) {
        try {
            return BossBar.Color.valueOf(String.valueOf(raw).strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BossBar.Color.RED;
        }
    }

    /** Placeholder text of the bar, shared with the tests through {@link Msg}. */
    Component textFor(org.bukkit.command.CommandSender viewer, Punishment sentence, Instant now) {
        Component remaining = sentence.expires() == null
                ? ctx.messages().render(viewer, "moderation.permanent")
                : Component.text(dev.yanianz.atrium.core.util.Durations.format(
                        sentence.remaining(now).orElse(Duration.ZERO), 2));
        return ctx.messages().render(viewer, "moderation.jail.bossbar",
                Msg.unparsed("jail", sentence.context() == null ? "" : sentence.context()),
                Msg.component("remaining", remaining));
    }
}
