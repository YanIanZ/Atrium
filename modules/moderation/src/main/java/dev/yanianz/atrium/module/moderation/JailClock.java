package dev.yanianz.atrium.module.moderation;

import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Sentence accounting rules that are not enforced by the time comparison alone: a sentence that should not
 * run while the prisoner is offline or away from the keyboard is extended by that time instead of being
 * frozen in storage.
 */
final class JailClock {
    /** A clock jump or a very long absence cannot extend a sentence by more than this. */
    static final Duration MAX_EXTENSION = Duration.ofDays(365);

    private JailClock() {}

    /**
     * Time a sentence is extended after a logout. A pause is only recorded when the countdown should not run
     * while offline, so the whole absence is added back. A clock that jumped backwards yields zero.
     */
    static Duration offlineExtension(@Nullable Instant pausedAt, Instant now) {
        if (pausedAt == null) {
            return Duration.ZERO;
        }
        Duration away = Duration.between(pausedAt, now);
        if (away.isNegative() || away.isZero()) {
            return Duration.ZERO;
        }
        return away.compareTo(MAX_EXTENSION) > 0 ? MAX_EXTENSION : away;
    }

    /**
     * Time a sentence is extended for one check interval spent AFK. The elapsed time is measured on the
     * monotonic clock, so a wall-clock adjustment can never shorten or stretch a sentence.
     */
    static Duration afkExtension(long elapsedNanos) {
        if (elapsedNanos <= 0) {
            return Duration.ZERO;
        }
        Duration elapsed = Duration.ofNanos(elapsedNanos);
        return elapsed.compareTo(MAX_EXTENSION) > 0 ? MAX_EXTENSION : elapsed;
    }
}
