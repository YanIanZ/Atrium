package dev.yanianz.atrium.module.portals;

import java.util.Locale;
import java.util.Objects;

/**
 * Axis-aligned block box in one world. Corners are block coordinates and inclusive, so the box covers the
 * continuous space {@code [min, max + 1)} on every axis. Immutable.
 */
record Cuboid(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    Cuboid {
        Objects.requireNonNull(world, "world");
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("min corner is above max corner");
        }
    }

    /** Box spanned by two block corners in any order. */
    static Cuboid of(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Cuboid(world, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    int sizeX() {
        return maxX - minX + 1;
    }

    int sizeY() {
        return maxY - minY + 1;
    }

    int sizeZ() {
        return maxZ - minZ + 1;
    }

    long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    int maxSide() {
        return Math.max(sizeX(), Math.max(sizeY(), sizeZ()));
    }

    boolean containsBlock(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** True when the point lies in the block space of the box (the max face is exclusive). */
    boolean contains(double x, double y, double z) {
        return x >= minX && x < maxX + 1.0 && y >= minY && y < maxY + 1.0 && z >= minZ && z < maxZ + 1.0;
    }

    boolean overlaps(Cuboid other) {
        return world.equals(other.world)
                && minX <= other.maxX && maxX >= other.minX
                && minY <= other.maxY && maxY >= other.minY
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    int minChunkX() {
        return minX >> 4;
    }

    int maxChunkX() {
        return maxX >> 4;
    }

    int minChunkZ() {
        return minZ >> 4;
    }

    int maxChunkZ() {
        return maxZ >> 4;
    }

    /**
     * Whether the straight segment from {@code (x0,y0,z0)} to {@code (x1,y1,z1)} touches the box (slab test).
     * Catches fast movement (elytra, riptide) that jumps over a thin portal between two move events.
     */
    boolean intersectsSegment(double x0, double y0, double z0, double x1, double y1, double z1) {
        double[] range = {0.0, 1.0};
        return clip(x0, x1 - x0, minX, maxX + 1.0, range)
                && clip(y0, y1 - y0, minY, maxY + 1.0, range)
                && clip(z0, z1 - z0, minZ, maxZ + 1.0, range);
    }

    private static boolean clip(double start, double delta, double low, double high, double[] range) {
        if (delta == 0.0) {
            return start >= low && start < high;
        }
        double t1 = (low - start) / delta;
        double t2 = (high - start) / delta;
        if (t1 > t2) {
            double swap = t1;
            t1 = t2;
            t2 = swap;
        }
        range[0] = Math.max(range[0], t1);
        range[1] = Math.min(range[1], t2);
        return range[0] <= range[1];
    }

    /** Squared distance from a point to the box (0 inside). */
    double distanceSquared(double x, double y, double z) {
        double dx = Math.max(Math.max(minX - x, 0.0), x - (maxX + 1.0));
        double dy = Math.max(Math.max(minY - y, 0.0), y - (maxY + 1.0));
        double dz = Math.max(Math.max(minZ - z, 0.0), z - (maxZ + 1.0));
        return dx * dx + dy * dy + dz * dz;
    }

    /** Locale-independent text, e.g. {@code world 1,64,-3 -> 4,67,-1}. */
    String describe() {
        return String.format(Locale.ROOT, "%s %d,%d,%d -> %d,%d,%d", world, minX, minY, minZ, maxX, maxY, maxZ);
    }
}
