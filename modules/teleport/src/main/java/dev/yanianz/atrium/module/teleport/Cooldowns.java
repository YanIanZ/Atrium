package dev.yanianz.atrium.module.teleport;

import dev.yanianz.atrium.api.teleport.TeleportService.TeleportCause;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player, per-cause teleport cooldowns. Thread-safe. This class is the in-memory view; the teleport
 * service writes every change through to {@link TeleportStore} and restores the stored rows on enable, so
 * cooldowns survive restarts.
 */
final class Cooldowns {
    private record Key(UUID player, TeleportCause cause) {}

    private final Map<Key, Instant> until = new ConcurrentHashMap<>();
    private final Clock clock;

    Cooldowns(Clock clock) {
        this.clock = clock;
    }

    /** Remaining time, or {@link Duration#ZERO} when the player may teleport. */
    Duration remaining(UUID player, TeleportCause cause) {
        Instant end = until.get(new Key(player, cause));
        if (end == null) {
            return Duration.ZERO;
        }
        Duration left = Duration.between(clock.instant(), end);
        if (left.isNegative() || left.isZero()) {
            until.remove(new Key(player, cause), end);
            return Duration.ZERO;
        }
        return left;
    }

    /** Starts a cooldown; a zero or negative length clears it. Returns the end, or null when cleared. */
    Instant start(UUID player, TeleportCause cause, Duration length) {
        Key key = new Key(player, cause);
        if (length.isNegative() || length.isZero()) {
            until.remove(key);
            return null;
        }
        Instant end = clock.instant().plus(length);
        until.put(key, end);
        return end;
    }

    /** Restores a persisted cooldown; ignored when it already ended or a later one is known. */
    void restore(UUID player, TeleportCause cause, Instant end) {
        if (!end.isAfter(clock.instant())) {
            return;
        }
        until.merge(new Key(player, cause), end, (a, b) -> a.isAfter(b) ? a : b);
    }

    Instant now() {
        return clock.instant();
    }

    void clear(UUID player) {
        until.keySet().removeIf(k -> k.player().equals(player));
    }

    /** Drops expired entries (called periodically). */
    void purge() {
        Instant now = clock.instant();
        until.values().removeIf(end -> !end.isAfter(now));
    }
}
