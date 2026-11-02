package dev.yanianz.atrium.module.itemtools;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A tiny in-memory rate gate per player and key. Used to swallow the second half of a double click on an
 * item and to stop the low-durability warning from repeating every hit. Nothing is persisted: after a
 * restart the first click always works, which is the safe direction for an anti-duplication gate.
 */
final class RateGate {
    private final Map<UUID, Map<String, Long>> last = new ConcurrentHashMap<>();

    /** True when this call is the first one within {@code intervalMillis} (or the interval is not positive). */
    boolean tryAcquire(UUID player, String key, long nowMillis, long intervalMillis) {
        if (player == null || key == null) {
            return false;
        }
        if (intervalMillis <= 0) {
            return true;
        }
        Map<String, Long> perPlayer = last.computeIfAbsent(player, ignored -> new ConcurrentHashMap<>());
        boolean[] acquired = {false};
        perPlayer.compute(key, (ignored, previous) -> {
            if (previous != null && nowMillis >= previous && nowMillis - previous < intervalMillis) {
                return previous;
            }
            acquired[0] = true;
            return nowMillis;
        });
        return acquired[0];
    }

    void forget(UUID player) {
        if (player != null) {
            last.remove(player);
        }
    }

    /** Keys currently tracked for a player; used by tests and diagnostics. */
    int tracked(UUID player) {
        Map<String, Long> perPlayer = last.get(player);
        return perPlayer == null ? 0 : perPlayer.size();
    }
}
