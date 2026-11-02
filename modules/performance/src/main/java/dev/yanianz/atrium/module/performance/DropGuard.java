package dev.yanianz.atrium.module.performance;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Short-lived protection for items that must never be removed by the per-chunk item cap: death loot,
 * Q-drops and the contents of a broken container. The events that announce them fire immediately before
 * vanilla spawns the items, on the same region thread, so a small window per chunk is enough and cannot
 * affect natural spawns once it expires.
 *
 * <p>Keyed by chunk only, so every thread can record and read it without touching an entity. Keys are
 * mixed hashes of the world UUID and the chunk coordinates, which is why a test can build them without
 * a running server.
 */
final class DropGuard {
    /** Vanilla spawns the announced items within the same tick; a generous window keeps it safe. */
    static final long WINDOW_MILLIS = 2_000L;
    /** After this many guarded chunks, expired entries are pruned. */
    private static final int PRUNE_AT = 1_024;

    private final Map<Long, Long> until = new ConcurrentHashMap<>();

    /** Marks a chunk as producing protected items until {@code now + WINDOW_MILLIS}. */
    void protect(long chunkKey, long now) {
        until.put(chunkKey, now + WINDOW_MILLIS);
        if (until.size() > PRUNE_AT) {
            prune(now);
        }
    }

    /** Whether an item spawning in this chunk right now came from a protected source. */
    boolean isProtected(long chunkKey, long now) {
        Long expiry = until.get(chunkKey);
        return expiry != null && now <= expiry;
    }

    void prune(long now) {
        until.entrySet().removeIf(entry -> entry.getValue() < now);
    }

    /** @return {@code Long.MIN_VALUE} for a location without a world, which is never protected. */
    static long chunkKey(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return Long.MIN_VALUE;
        }
        return chunkKey(world.getUID().getMostSignificantBits() ^ world.getUID().getLeastSignificantBits(),
                location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    /** Stable key for one chunk of one world; a hash collision only widens the protection briefly. */
    static long chunkKey(long worldSeed, int chunkX, int chunkZ) {
        long key = worldSeed;
        key = key * 0x9E3779B97F4A7C15L + chunkX;
        key = key * 0x9E3779B97F4A7C15L + chunkZ;
        return key;
    }
}
