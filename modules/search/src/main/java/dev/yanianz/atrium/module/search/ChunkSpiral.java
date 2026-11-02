package dev.yanianz.atrium.module.search;

import java.util.ArrayList;
import java.util.List;

/**
 * Chunk order for the container scan: concentric square rings around the centre, innermost first,
 * each ring walked clockwise exactly once. Unlike an index-count spiral, the full square is always
 * visited: the order is generated from the area, so a partly generated world can never terminate the
 * scan early.
 */
public final class ChunkSpiral {
    public record Pos(int x, int z) {}

    private ChunkSpiral() {}

    /** Number of chunks in a square of {@code radius} chunks around the centre. */
    public static int count(int radius) {
        if (radius < 0) {
            throw new IllegalArgumentException("radius must be >= 0");
        }
        int side = 2 * radius + 1;
        return side * side;
    }

    public static List<Pos> around(int centerX, int centerZ, int radius) {
        if (radius < 0) {
            throw new IllegalArgumentException("radius must be >= 0");
        }
        List<Pos> out = new ArrayList<>(count(radius));
        out.add(new Pos(centerX, centerZ));
        for (int r = 1; r <= radius; r++) {
            for (int x = -r; x <= r; x++) {
                out.add(new Pos(centerX + x, centerZ - r)); // top edge
            }
            for (int z = -r + 1; z <= r; z++) {
                out.add(new Pos(centerX + r, centerZ + z)); // right edge
            }
            for (int x = r - 1; x >= -r; x--) {
                out.add(new Pos(centerX + x, centerZ + r)); // bottom edge
            }
            for (int z = r - 1; z >= -r + 1; z--) {
                out.add(new Pos(centerX - r, centerZ + z)); // left edge
            }
        }
        return out;
    }
}
