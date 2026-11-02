package dev.yanianz.atrium.module.bossbar;

import dev.yanianz.atrium.module.display.Animations;
import dev.yanianz.atrium.module.display.Targeting;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import net.kyori.adventure.bossbar.BossBar;

/** Immutable, validated view of {@link BossbarConfig}; replaced as a whole on reload. */
record BossbarSettings(
        int intervalTicks,
        boolean announcementsEnabled,
        List<AnnouncementSpec> announcements,
        Rotation rotation,
        Animations animations,
        BossBar.Color messageColor,
        BossBar.Overlay messageOverlay,
        int maxMessageSeconds,
        int maxMessageLength,
        int maxMessageBars) {

    record AnnouncementSpec(String text, Targeting targeting, BossBar.Color color, BossBar.Overlay overlay,
            boolean countdown, double progress) {}

    static BossbarSettings from(BossbarConfig config, BiConsumer<String, String> problems) {
        int interval = clamp(config.updateIntervalTicks, 2, 200, "update-interval-ticks", problems);
        int intervalSeconds = clamp(config.intervalSeconds, 1, 86_400, "interval-seconds", problems);
        List<AnnouncementSpec> specs = new ArrayList<>();
        List<Long> durations = new ArrayList<>();
        if (config.announcements != null) {
            int i = 0;
            for (BossbarConfig.Announcement a : config.announcements) {
                String where = "announcements[" + i++ + "]";
                if (a == null) {
                    continue;
                }
                int seconds = a.seconds;
                if (seconds < 1) {
                    problems.accept(where, "seconds must be at least 1; using 1");
                    seconds = 1;
                }
                if (seconds > intervalSeconds) {
                    problems.accept(where, "seconds is longer than interval-seconds; it is cut to " + intervalSeconds);
                }
                String mode = a.progressMode == null ? "countdown" : a.progressMode.toLowerCase(Locale.ROOT);
                if (!mode.equals("countdown") && !mode.equals("static")) {
                    problems.accept(where, "unknown progress-mode '" + a.progressMode + "'; using countdown");
                    mode = "countdown";
                }
                double progress = a.progress;
                if (Double.isNaN(progress) || progress < 0 || progress > 1) {
                    problems.accept(where, "progress must be 0.0-1.0; using 1.0");
                    progress = 1.0;
                }
                specs.add(new AnnouncementSpec(a.text == null ? "" : a.text, new Targeting(a.worlds, a.permission),
                        color(a.color, BossBar.Color.BLUE, where + ".color", problems),
                        overlay(a.overlay, BossBar.Overlay.PROGRESS, where + ".overlay", problems),
                        mode.equals("countdown"), progress));
                durations.add(seconds * 20L);
            }
        }
        return new BossbarSettings(interval, config.announcementsEnabled, List.copyOf(specs),
                new Rotation(intervalSeconds * 20L, durations, config.randomOrder),
                Animations.from(config.animations, (n, problem) -> problems.accept("animations." + n, problem)),
                color(config.messageColor, BossBar.Color.YELLOW, "message-color", problems),
                overlay(config.messageOverlay, BossBar.Overlay.PROGRESS, "message-overlay", problems),
                clamp(config.maxMessageSeconds, 1, 86_400, "max-message-seconds", problems),
                clamp(config.maxMessageLength, 1, 1024, "max-message-length", problems),
                clamp(config.maxMessageBarsPerPlayer, 1, 10, "max-message-bars-per-player", problems));
    }

    static int clamp(int value, int min, int max, String option, BiConsumer<String, String> problems) {
        if (value < min || value > max) {
            int fixed = Math.clamp(value, min, max);
            problems.accept(option, value + " is outside " + min + "-" + max + "; using " + fixed);
            return fixed;
        }
        return value;
    }

    static BossBar.Color color(String name, BossBar.Color fallback, String option, BiConsumer<String, String> problems) {
        BossBar.Color color = name == null ? null : BossBar.Color.NAMES.value(name.trim().toLowerCase(Locale.ROOT));
        if (color == null) {
            problems.accept(option, "unknown color '" + name + "'; using " + BossBar.Color.NAMES.key(fallback));
            return fallback;
        }
        return color;
    }

    static BossBar.Overlay overlay(String name, BossBar.Overlay fallback, String option,
            BiConsumer<String, String> problems) {
        BossBar.Overlay overlay = name == null ? null : BossBar.Overlay.NAMES.value(name.trim().toLowerCase(Locale.ROOT));
        if (overlay == null) {
            problems.accept(option, "unknown overlay '" + name + "'; using " + BossBar.Overlay.NAMES.key(fallback));
            return fallback;
        }
        return overlay;
    }
}
