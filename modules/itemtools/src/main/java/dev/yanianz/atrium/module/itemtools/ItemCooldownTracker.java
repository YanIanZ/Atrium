package dev.yanianz.atrium.module.itemtools;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player expiry of item cooldowns, keyed by the id stored on the item. Memory only: a cooldown is a
 * gameplay nicety, and a restart resetting it is better than a database write on every click. Entries are
 * dropped when the player quits.
 */
final class ItemCooldownTracker {
    private final Map<UUID, Map<String, Long>> readyAt = new ConcurrentHashMap<>();

    /** Remaining whole seconds (rounded up); 0 when the cooldown is over or was never started. */
    int remainingSeconds(UUID player, String id, long nowMillis) {
        Long until = expiry(player, id);
        return until == null ? 0 : ItemCooldown.remainingSeconds(nowMillis, until);
    }

    boolean ready(UUID player, String id, long nowMillis) {
        Long until = expiry(player, id);
        return until == null || ItemCooldown.ready(nowMillis, until);
    }

    /** Starts (or restarts) the cooldown; a non-positive amount clears it instead. */
    void start(UUID player, String id, long nowMillis, int seconds) {
        if (player == null || id == null) {
            return;
        }
        if (seconds <= 0) {
            Map<String, Long> perPlayer = readyAt.get(player);
            if (perPlayer != null) {
                perPlayer.remove(id);
            }
            return;
        }
        readyAt.computeIfAbsent(player, ignored -> new ConcurrentHashMap<>())
                .put(id, ItemCooldown.readyAt(nowMillis, seconds));
    }

    void forget(UUID player) {
        if (player != null) {
            readyAt.remove(player);
        }
    }

    int tracked(UUID player) {
        Map<String, Long> perPlayer = readyAt.get(player);
        return perPlayer == null ? 0 : perPlayer.size();
    }

    private Long expiry(UUID player, String id) {
        if (player == null || id == null) {
            return null;
        }
        Map<String, Long> perPlayer = readyAt.get(player);
        return perPlayer == null ? null : perPlayer.get(id);
    }
}
