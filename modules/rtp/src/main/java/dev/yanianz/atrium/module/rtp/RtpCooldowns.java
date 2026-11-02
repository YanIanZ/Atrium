package dev.yanianz.atrium.module.rtp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player, per-world /rtp cooldowns. Thread-safe in-memory view; {@link RtpModule} writes every new
 * cooldown through to {@link RtpStore} and restores stored rows on enable, so they survive restarts.
 */
final class RtpCooldowns {
    private record Key(UUID player, String world) {}

    private final Map<Key, Instant> until = new ConcurrentHashMap<>();
    private final Clock clock;

    RtpCooldowns(Clock clock) {
        this.clock = clock;
    }

    static String worldKey(String world) {
        return world.toLowerCase(Locale.ROOT);
    }

    /** Remaining time, or {@link Duration#ZERO}. */
    Duration remaining(UUID player, String world) {
        Key key = new Key(player, worldKey(world));
        Instant end = until.get(key);
        if (end == null) {
            return Duration.ZERO;
        }
        Duration left = Duration.between(clock.instant(), end);
        if (left.isNegative() || left.isZero()) {
            until.remove(key, end);
            return Duration.ZERO;
        }
        return left;
    }

    /** Starts a cooldown and returns its end, or null when {@code length} is not positive. */
    Instant start(UUID player, String world, Duration length) {
        if (length.isNegative() || length.isZero()) {
            return null;
        }
        Instant end = clock.instant().plus(length);
        until.put(new Key(player, worldKey(world)), end);
        return end;
    }

    /** Restores a stored cooldown; ignored when it already ended or a later one is known. */
    void restore(UUID player, String world, Instant end) {
        if (end.isAfter(clock.instant())) {
            until.merge(new Key(player, worldKey(world)), end, (a, b) -> a.isAfter(b) ? a : b);
        }
    }

    void purge() {
        Instant now = clock.instant();
        until.values().removeIf(end -> !end.isAfter(now));
    }
}
