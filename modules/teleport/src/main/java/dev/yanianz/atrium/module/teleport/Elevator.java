package dev.yanianz.atrium.module.teleport;

import java.util.List;
import java.util.OptionalInt;
import java.util.function.IntPredicate;

/**
 * Sign elevator search. A stop is any block matching the configured patterns; the player is placed on
 * top of the destination stop. Pure logic: the caller supplies a predicate that reads the real blocks and
 * runs on the region thread.
 */
final class Elevator {
    private Elevator() {}

    static boolean matches(List<String> patterns, String materialName) {
        return BlockPatterns.matches(patterns, materialName);
    }

    /**
     * The first stop at least {@code gap} blocks above {@code fromY}, up to {@code maxY} (inclusive).
     */
    static OptionalInt above(int fromY, int gap, int maxY, IntPredicate stop) {
        int start = fromY + Math.max(1, gap);
        for (int y = start; y <= maxY; y++) {
            if (stop.test(y)) {
                return OptionalInt.of(y);
            }
        }
        return OptionalInt.empty();
    }

    /** The first stop at least {@code gap} blocks below {@code fromY}, down to {@code minY} (inclusive). */
    static OptionalInt below(int fromY, int gap, int minY, IntPredicate stop) {
        int start = fromY - Math.max(1, gap);
        for (int y = start; y >= minY; y--) {
            if (stop.test(y)) {
                return OptionalInt.of(y);
            }
        }
        return OptionalInt.empty();
    }

    /** The landing window: only a couple of blocks, so an elevator never jumps to another floor. */
    static TeleportConfig.SafeLanding window(TeleportConfig.SafeLanding global) {
        TeleportConfig.SafeLanding window = new TeleportConfig.SafeLanding();
        window.causes = global.causes;
        window.searchUp = 2;
        window.searchDown = 2;
        window.searchRadius = 0;
        window.allowWater = false;
        window.allowNetherRoof = global.allowNetherRoof;
        return window;
    }
}
