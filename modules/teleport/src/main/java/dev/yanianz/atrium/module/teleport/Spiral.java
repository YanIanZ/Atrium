package dev.yanianz.atrium.module.teleport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Column offsets around a target, nearest first: the target itself, then ring by ring (Chebyshev distance),
 * inside a ring by real distance (the four direct neighbours before the diagonals) and finally clockwise
 * starting north, so the order is fully deterministic.
 */
final class Spiral {
    /** Largest supported radius; (2r+1)² columns are searched, so keep it small. */
    static final int MAX_RADIUS = 8;

    record Offset(int dx, int dz) {
        int ring() {
            return Math.max(Math.abs(dx), Math.abs(dz));
        }

        int distanceSquared() {
            return dx * dx + dz * dz;
        }

        /** Clockwise angle from north (-z) in [0, 2π). */
        double angle() {
            double a = Math.atan2(dx, -dz);
            return a < 0 ? a + 2 * Math.PI : a;
        }
    }

    private static final Comparator<Offset> ORDER = Comparator.comparingInt(Offset::ring)
            .thenComparingInt(Offset::distanceSquared)
            .thenComparingDouble(Offset::angle);

    private static final ConcurrentHashMap<Integer, List<Offset>> CACHE = new ConcurrentHashMap<>();

    private Spiral() {}

    /** All offsets with ring ≤ radius (radius clamped to [0, {@link #MAX_RADIUS}]), starting with (0, 0). */
    static List<Offset> offsets(int radius) {
        int r = Math.max(0, Math.min(MAX_RADIUS, radius));
        return CACHE.computeIfAbsent(r, Spiral::compute);
    }

    /**
     * The offsets except (0, 0), in spiral order, split into consecutive runs whose columns lie in the same
     * chunk (relative to the block column {@code bx, bz}). Each run is searched with one chunk load and one
     * hop to the owning region.
     */
    static List<List<Offset>> runsByChunk(int bx, int bz, int radius) {
        List<List<Offset>> runs = new ArrayList<>();
        List<Offset> current = null;
        long currentChunk = 0;
        for (Offset offset : offsets(radius)) {
            if (offset.dx() == 0 && offset.dz() == 0) {
                continue;
            }
            long chunk = chunkKey((bx + offset.dx()) >> 4, (bz + offset.dz()) >> 4);
            if (current == null || chunk != currentChunk) {
                current = new ArrayList<>();
                runs.add(current);
                currentChunk = chunk;
            }
            current.add(offset);
        }
        return runs;
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    private static List<Offset> compute(int r) {
        List<Offset> list = new ArrayList<>((2 * r + 1) * (2 * r + 1));
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                list.add(new Offset(dx, dz));
            }
        }
        list.sort(ORDER);
        return List.copyOf(list);
    }
}
