package dev.yanianz.atrium.module.elytra;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cooldowns of a few seconds (boost, launch). They are not worth a database write per sneak, and
 * losing them on restart is harmless. Atomic per key, safe from any thread.
 */
final class ShortCooldowns {
    private record Key(UUID player, String kind) {}

    private final Map<Key, Long> until = new ConcurrentHashMap<>();

    /** Starts the cooldown when it is not running. Returns 0 on success, otherwise the milliseconds left. */
    long tryAcquire(UUID player, String kind, long nowMillis, long durationMillis) {
        long[] left = {0};
        until.compute(new Key(player, kind), (k, expires) -> {
            if (expires != null && expires > nowMillis) {
                left[0] = expires - nowMillis;
                return expires;
            }
            return durationMillis <= 0 ? null : nowMillis + durationMillis;
        });
        return left[0];
    }

    void clear(UUID player) {
        until.keySet().removeIf(k -> k.player().equals(player));
    }

    void clearAll() {
        until.clear();
    }
}
