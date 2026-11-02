package dev.yanianz.atrium.module.mobs;

import java.util.ArrayList;
import java.util.List;

/** Chunks whose area intersects a circle of {@code radius} blocks around a block position. */
final class ChunkArea {
    record ChunkPos(int x, int z) {}

    private ChunkArea() {}

    static List<ChunkPos> around(int blockX, int blockZ, int radius) {
        if (radius < 0) {
            throw new IllegalArgumentException("radius must be >= 0");
        }
        int minCx = (blockX - radius) >> 4;
        int maxCx = (blockX + radius) >> 4;
        int minCz = (blockZ - radius) >> 4;
        int maxCz = (blockZ + radius) >> 4;
        long r2 = (long) radius * radius;
        List<ChunkPos> out = new ArrayList<>();
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                long dx = Math.max(0, Math.max((cx << 4) - blockX, blockX - ((cx << 4) + 15)));
                long dz = Math.max(0, Math.max((cz << 4) - blockZ, blockZ - ((cz << 4) + 15)));
                if (dx * dx + dz * dz <= r2) {
                    out.add(new ChunkPos(cx, cz));
                }
            }
        }
        return out;
    }
}
