package dev.yanianz.atrium.module.mobs;

/** Pure proximity check used when a spawner is placed. */
final class SpawnerProximity {
    private SpawnerProximity() {}

    record Pos(int x, int y, int z) {}

    /**
     * Squared distance to the nearest other spawner inside {@code radius}, or {@code -1} when the area is
     * clear. The position being placed is ignored. Callers only pass spawners from loaded chunks, so no
     * chunk is ever loaded to answer this question.
     */
    static double nearest(int x, int y, int z, int radius, Iterable<Pos> spawners) {
        if (radius < 0) {
            throw new IllegalArgumentException("radius must not be negative");
        }
        double limit = (double) radius * radius;
        double nearest = -1;
        for (Pos pos : spawners) {
            if (pos.x() == x && pos.y() == y && pos.z() == z) {
                continue;
            }
            double dx = pos.x() - x;
            double dy = pos.y() - y;
            double dz = pos.z() - z;
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance <= limit && (nearest < 0 || distance < nearest)) {
                nearest = distance;
            }
        }
        return nearest;
    }
}
