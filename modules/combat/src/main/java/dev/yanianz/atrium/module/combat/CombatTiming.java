package dev.yanianz.atrium.module.combat;

/** Pure combat-timer maths, independent of Bukkit state. */
final class CombatTiming {
    private CombatTiming() {}

    /** Milliseconds left until {@code untilMillis}; never negative. */
    static long remaining(long nowMillis, long untilMillis) {
        return Math.max(0L, untilMillis - nowMillis);
    }

    /** Ends a tag at the later of the running end and a fresh duration from now. */
    static long extendedUntil(long currentUntilMillis, long nowMillis, long durationMillis) {
        return Math.max(currentUntilMillis, nowMillis + Math.max(0L, durationMillis));
    }

    /** Whole seconds shown to players; a running tag never shows 0. */
    static int secondsLeft(long remainingMillis) {
        if (remainingMillis <= 0) {
            return 0;
        }
        return (int) Math.ceil(remainingMillis / 1000.0);
    }

    /** Boss bar progress in 0..1; a missing total reports an empty bar. */
    static float progress(long remainingMillis, long totalMillis) {
        if (remainingMillis <= 0 || totalMillis <= 0) {
            return 0.0f;
        }
        return (float) Math.max(0.0, Math.min(1.0, (double) remainingMillis / (double) totalMillis));
    }
}
