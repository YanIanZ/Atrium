package dev.yanianz.atrium.module.playerplus;

import java.time.Duration;

/**
 * Pure countdown math for temporary flight.
 *
 * <p>Only the remaining duration is persisted, never an absolute expiry timestamp: time spent offline is
 * therefore never consumed. While online the remaining time is derived from the deadline captured when
 * the session started. A clock that moves backwards is treated as no elapsed time so it can never extend
 * or skip a countdown.
 */
final class TflyMath {
    private TflyMath() {}

    /** Remaining time for a session that started at {@code onlineSinceMillis}. */
    static long remaining(long storedRemainingMillis, long onlineSinceMillis, long nowMillis) {
        long stored = Math.max(0, storedRemainingMillis);
        long elapsed = Math.max(0, nowMillis - onlineSinceMillis);
        return Math.max(0, stored - elapsed);
    }

    /** Whether the warning for the last seconds has to be shown now. */
    static boolean shouldWarn(long remainingMillis, boolean alreadyWarned, long warningMillis) {
        return !alreadyWarned && remainingMillis > 0 && warningMillis > 0 && remainingMillis <= warningMillis;
    }

    /** Adds {@code addedMillis} to the current time, capped at the configured maximum. */
    static long extend(long currentMillis, long addedMillis, long maxMillis) {
        long current = Math.max(0, currentMillis);
        long added = Math.max(0, addedMillis);
        long max = Math.max(0, maxMillis);
        long sum;
        try {
            sum = Math.addExact(current, added);
        } catch (ArithmeticException e) {
            sum = Long.MAX_VALUE;
        }
        return Math.min(sum, max);
    }

    /** Rounds a duration up to whole seconds for display and persistence of coarse values. */
    static long ceilSeconds(Duration duration) {
        long millis = Math.max(0, duration.toMillis());
        return millis == 0 ? 0 : (millis + 999) / 1000;
    }
}
