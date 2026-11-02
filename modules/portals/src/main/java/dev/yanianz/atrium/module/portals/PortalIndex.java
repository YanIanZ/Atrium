package dev.yanianz.atrium.module.portals;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable spatial index: world, then chunk key, then the portals touching that chunk. A move check only
 * looks at the portals of one (or a few) chunk buckets, so its cost does not grow with the total number of
 * portals. The index is rebuilt from scratch on every change and published through a volatile field, so
 * readers on any region thread always see a complete, consistent snapshot (no stale buckets are kept).
 */
final class PortalIndex {
    static final PortalIndex EMPTY = new PortalIndex(Map.of(), Map.of());

    /** Segments spanning more chunks than this are treated like a jump: only the end point counts. */
    static final int MAX_SEGMENT_CHUNKS = 16;

    private static final Comparator<Portal> ORDER = Comparator.comparing(Portal::key);

    private final Map<String, Map<Long, List<Portal>>> buckets;
    private final Map<String, List<Portal>> byWorld;

    private PortalIndex(Map<String, Map<Long, List<Portal>>> buckets, Map<String, List<Portal>> byWorld) {
        this.buckets = buckets;
        this.byWorld = byWorld;
    }

    static PortalIndex build(Collection<Portal> portals) {
        if (portals.isEmpty()) {
            return EMPTY;
        }
        Map<String, Map<Long, List<Portal>>> buckets = new HashMap<>();
        Map<String, List<Portal>> byWorld = new HashMap<>();
        List<Portal> sorted = new ArrayList<>(portals);
        sorted.sort(ORDER);
        for (Portal portal : sorted) {
            Cuboid area = portal.area();
            byWorld.computeIfAbsent(area.world(), w -> new ArrayList<>()).add(portal);
            Map<Long, List<Portal>> world = buckets.computeIfAbsent(area.world(), w -> new HashMap<>());
            for (int cx = area.minChunkX(); cx <= area.maxChunkX(); cx++) {
                for (int cz = area.minChunkZ(); cz <= area.maxChunkZ(); cz++) {
                    world.computeIfAbsent(chunkKey(cx, cz), k -> new ArrayList<>()).add(portal);
                }
            }
        }
        Map<String, Map<Long, List<Portal>>> frozen = new HashMap<>();
        buckets.forEach((world, map) -> {
            Map<Long, List<Portal>> copy = new HashMap<>();
            map.forEach((key, list) -> copy.put(key, List.copyOf(list)));
            frozen.put(world, Map.copyOf(copy));
        });
        Map<String, List<Portal>> worlds = new HashMap<>();
        byWorld.forEach((world, list) -> worlds.put(world, List.copyOf(list)));
        return new PortalIndex(Map.copyOf(frozen), Map.copyOf(worlds));
    }

    /** Packs chunk coordinates into one long (x in the high half, z in the low half). */
    static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    static int chunkOf(double coordinate) {
        return ((int) Math.floor(coordinate)) >> 4;
    }

    boolean isEmpty() {
        return byWorld.isEmpty();
    }

    boolean hasWorld(String world) {
        return byWorld.containsKey(world);
    }

    /** Every portal of the world, sorted by name. */
    List<Portal> inWorld(String world) {
        return byWorld.getOrDefault(world, List.of());
    }

    /** Portals whose box touches the chunk. */
    List<Portal> bucket(String world, int chunkX, int chunkZ) {
        Map<Long, List<Portal>> map = buckets.get(world);
        if (map == null) {
            return List.of();
        }
        return map.getOrDefault(chunkKey(chunkX, chunkZ), List.of());
    }

    int bucketCount(String world) {
        Map<Long, List<Portal>> map = buckets.get(world);
        return map == null ? 0 : map.size();
    }

    /** Portals containing the point, in name order. */
    List<Portal> containing(String world, double x, double y, double z) {
        List<Portal> candidates = bucket(world, chunkOf(x), chunkOf(z));
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Portal> out = new ArrayList<>(1);
        for (Portal portal : candidates) {
            if (portal.area().contains(x, y, z)) {
                out.add(portal);
            }
        }
        return out;
    }

    /**
     * Portals touched by the movement from one point to another in the same world (including those that
     * contain the end point), in name order. Very long segments only check the end point.
     */
    List<Portal> crossedBy(String world, double x0, double y0, double z0, double x1, double y1, double z1) {
        Map<Long, List<Portal>> map = buckets.get(world);
        if (map == null) {
            return List.of();
        }
        int cx0 = Math.min(chunkOf(x0), chunkOf(x1));
        int cx1 = Math.max(chunkOf(x0), chunkOf(x1));
        int cz0 = Math.min(chunkOf(z0), chunkOf(z1));
        int cz1 = Math.max(chunkOf(z0), chunkOf(z1));
        long chunks = (long) (cx1 - cx0 + 1) * (cz1 - cz0 + 1);
        if (chunks > MAX_SEGMENT_CHUNKS) {
            return containing(world, x1, y1, z1);
        }
        if (chunks == 1) {
            List<Portal> candidates = map.getOrDefault(chunkKey(cx0, cz0), List.of());
            if (candidates.isEmpty()) {
                return List.of();
            }
            List<Portal> out = new ArrayList<>(1);
            for (Portal portal : candidates) {
                if (portal.area().intersectsSegment(x0, y0, z0, x1, y1, z1)) {
                    out.add(portal);
                }
            }
            return out;
        }
        Set<Portal> found = new LinkedHashSet<>();
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                for (Portal portal : map.getOrDefault(chunkKey(cx, cz), List.of())) {
                    if (!found.contains(portal) && portal.area().intersectsSegment(x0, y0, z0, x1, y1, z1)) {
                        found.add(portal);
                    }
                }
            }
        }
        List<Portal> out = new ArrayList<>(found);
        out.sort(ORDER);
        return out;
    }
}
