package dev.yanianz.atrium.module.worldrules;

/** Pure time-rate math: a multiplier turns into extra (or removed) ticks for one scheduler interval. */
final class TimeRateMath {
    static final long DAY = 24000L;

    private TimeRateMath() {}

    /** A multiplier outside the supported range or a non-finite value falls back to vanilla speed. */
    static double clampMultiplier(double value) {
        if (!Double.isFinite(value)) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(100.0, value));
    }

    /**
     * Ticks to add to a world after {@code elapsed} vanilla ticks. Vanilla already advanced the clock, so a
     * multiplier of 2 adds {@code elapsed} ticks and a multiplier of 0.5 removes half of them.
     */
    static long extraTicks(int elapsed, double multiplier) {
        if (elapsed <= 0) {
            return 0;
        }
        double clamped = clampMultiplier(multiplier);
        return Math.round((clamped - 1.0) * (double) elapsed);
    }

    /** Applies the extra ticks and wraps the result into one day. */
    static long advance(long time, long extra) {
        return Math.floorMod(time + extra, DAY);
    }
}
