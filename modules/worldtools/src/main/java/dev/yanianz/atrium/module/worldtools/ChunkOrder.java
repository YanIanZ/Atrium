package dev.yanianz.atrium.module.worldtools;

import java.util.ArrayList;
import java.util.List;

/** Square rings around a centre chunk, innermost first; each ring is walked clockwise exactly once. */
final class ChunkOrder {
    record ChunkPos(int x, int z) {}

    private ChunkOrder() {}

    static List<ChunkPos> rings(int centerX, int centerZ, int radius) {
        if (radius < 0) {
            throw new IllegalArgumentException("radius must be >= 0");
        }
        List<ChunkPos> out = new ArrayList<>((2 * radius + 1) * (2 * radius + 1));
        out.add(new ChunkPos(centerX, centerZ));
        for (int r = 1; r <= radius; r++) {
            for (int x = -r; x <= r; x++) {
                out.add(new ChunkPos(centerX + x, centerZ - r)); // top edge
            }
            for (int z = -r + 1; z <= r; z++) {
                out.add(new ChunkPos(centerX + r, centerZ + z)); // right edge
            }
            for (int x = r - 1; x >= -r; x--) {
                out.add(new ChunkPos(centerX + x, centerZ + r)); // bottom edge
            }
            for (int z = r - 1; z >= -r + 1; z--) {
                out.add(new ChunkPos(centerX - r, centerZ + z)); // left edge
            }
        }
        return out;
    }

    /** Chunks covering the block box [minX..maxX] x [minZ..maxZ], ordered by ring around the centre block. */
    static List<ChunkPos> covering(int centerBlockX, int centerBlockZ, int minX, int maxX, int minZ, int maxZ) {
        int cx = centerBlockX >> 4;
        int cz = centerBlockZ >> 4;
        int radius = Math.max(Math.max(cx - (minX >> 4), (maxX >> 4) - cx), Math.max(cz - (minZ >> 4), (maxZ >> 4) - cz));
        List<ChunkPos> out = new ArrayList<>();
        for (ChunkPos pos : rings(cx, cz, radius)) {
            if (pos.x() >= minX >> 4 && pos.x() <= maxX >> 4 && pos.z() >= minZ >> 4 && pos.z() <= maxZ >> 4) {
                out.add(pos);
            }
        }
        return out;
    }
}
