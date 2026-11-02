package dev.yanianz.atrium.module.worldrules;

import java.util.Locale;

/** Pure sleep helpers: percentages, night detection and command root parsing. */
final class SleepMath {
    /** Minecraft time of the sunrise, used when the night is skipped. */
    static final long SUNRISE = 0;

    private SleepMath() {}

    /** Sleeping fraction of the players that count, 0 when nobody counts. */
    static double percent(int sleeping, int total) {
        if (total <= 0 || sleeping <= 0) {
            return 0.0;
        }
        return Math.min(1.0, (double) sleeping / (double) total);
    }

    /** Extra ticks per second for a sleeping fraction; 0 while the fraction is below the requirement. */
    static double speed(double percent, double ticksPerSecond, double required) {
        double fraction = Math.max(0.0, Math.min(1.0, percent));
        double requiredFraction = Math.max(0.0, Math.min(1.0, required));
        if (fraction < requiredFraction || fraction <= 0) {
            return 0.0;
        }
        return Math.max(1.0, ticksPerSecond * fraction);
    }

    /** Night is dusk (12500) up to the point where the sun is almost up again (23000). */
    static boolean isNight(long time) {
        long t = Math.floorMod(time, 24000L);
        return t >= 12500L && t < 23000L;
    }

    /** Advances the world time by {@code ticks}, wrapping the day counter at sunrise. */
    static long advance(long time, double ticks) {
        if (!Double.isFinite(ticks) || ticks <= 0) {
            return time;
        }
        return time + Math.round(ticks);
    }

    /** The command without its slash, namespace and arguments, lower-cased. */
    static String commandRoot(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        while (text.startsWith("/")) {
            text = text.substring(1);
        }
        int space = text.indexOf(' ');
        if (space >= 0) {
            text = text.substring(0, space);
        }
        int colon = text.indexOf(':');
        if (colon >= 0) {
            text = text.substring(colon + 1);
        }
        return text.toLowerCase(Locale.ROOT);
    }
}
