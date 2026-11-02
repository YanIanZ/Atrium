package dev.yanianz.atrium.module.rtp;

import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Ready random-teleport spots per world, found in the background. Thread-safe. Spots are plain coordinates;
 * the caller checks a spot again on its region before using it, because the world may have changed.
 */
final class LocationPool {
    /** A found landing column; {@code y} is the feet block. */
    record Spot(int x, int y, int z, float yaw, long foundAt) {}

    private final Map<String, Deque<Spot>> pools = new ConcurrentHashMap<>();

    private Deque<Spot> pool(String world) {
        return pools.computeIfAbsent(world, w -> new ConcurrentLinkedDeque<>());
    }

    /** Adds a spot unless the world already holds {@code capacity} spots. Returns whether it was added. */
    boolean offer(String world, Spot spot, int capacity) {
        Deque<Spot> pool = pool(world);
        if (pool.size() >= capacity) {
            return false;
        }
        pool.addLast(spot);
        return true;
    }

    /** Takes the oldest spot that is not older than {@code maxAgeMillis}; older ones are dropped. */
    Optional<Spot> poll(String world, long now, long maxAgeMillis) {
        Deque<Spot> pool = pools.get(world);
        if (pool == null) {
            return Optional.empty();
        }
        Spot spot;
        while ((spot = pool.pollFirst()) != null) {
            if (now - spot.foundAt() <= maxAgeMillis) {
                return Optional.of(spot);
            }
        }
        return Optional.empty();
    }

    int size(String world) {
        Deque<Spot> pool = pools.get(world);
        return pool == null ? 0 : pool.size();
    }

    /** Drops spots older than {@code maxAgeMillis} in every world. */
    void expire(long now, long maxAgeMillis) {
        pools.values().forEach(pool -> pool.removeIf(spot -> now - spot.foundAt() > maxAgeMillis));
    }

    void clear() {
        pools.clear();
    }
}
