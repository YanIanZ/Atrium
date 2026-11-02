package dev.yanianz.atrium.module.worldtools;

import java.util.Optional;

/**
 * Where one placed or broken block has to be reproduced: the transformed position around the centre,
 * filtered by the configured radius. Pure, so the radius and "the centre block never copies itself"
 * rules are unit tested without a server.
 */
final class MirrorTargets {
    private MirrorTargets() {}

    record Target(int x, int y, int z) {}

    /**
     * Target of the block at {@code (x, y, z)} for the given transform around {@code (cx, cy, cz)}.
     *
     * @return empty when the block is the centre block itself (it maps onto itself) or when the target
     *         is farther away from the centre than {@code maxRadius} blocks
     */
    static Optional<Target> target(MirrorTransform transform, int cx, int cy, int cz, int x, int y, int z,
            int maxRadius) {
        int[] moved = transform.applyAt(cx, cy, cz, x, y, z);
        if (moved[0] == x && moved[1] == y && moved[2] == z) {
            return Optional.empty();
        }
        int radius = Math.max(1, maxRadius);
        if (outside(moved[0], moved[1], moved[2], cx, cy, cz, radius)) {
            return Optional.empty();
        }
        return Optional.of(new Target(moved[0], moved[1], moved[2]));
    }

    /** Chebyshev block distance: the target must stay inside a cube of {@code radius} around the centre. */
    static boolean outside(int x, int y, int z, int cx, int cy, int cz, int radius) {
        return Math.abs(x - cx) > radius || Math.abs(y - cy) > radius || Math.abs(z - cz) > radius;
    }
}
