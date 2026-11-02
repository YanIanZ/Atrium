package dev.yanianz.atrium.module.teleport;

import java.util.OptionalInt;

/**
 * Pure safe-landing logic over one block column. A player standing at block {@code y} occupies the feet
 * block {@code y} and the head block {@code y + 1}, and stands on the floor block {@code y - 1}.
 *
 * <p>A spot is safe when feet and head are free (no collision, no hazard), and the floor carries the
 * player (solid, or water when water is allowed). Nothing here touches the world, so it is unit tested
 * with a fake grid; {@link SafeLanding} feeds it real blocks on the region thread.
 */
final class SafeSpot {
    /** What a block means for a player. */
    enum Kind {
        /** No collision and harmless: air, grass, flowers, torches, open doors, ... */
        EMPTY,
        /** Has collision and can be stood on. */
        SOLID,
        /** Water: floats a player, never hurts on landing. */
        WATER,
        /** Hurts or traps: lava, fire, magma, cactus, berry bush, powder snow, cobweb, ... */
        HAZARD
    }

    /** Read access to the column being searched. Only called for {@code minY <= y < maxY}. */
    @FunctionalInterface
    interface Column {
        Kind at(int y);
    }

    private SafeSpot() {}

    /**
     * @param minY lowest block of the world (inclusive)
     * @param maxY ceiling (exclusive): the head block must be below it
     */
    static boolean isSafe(Column column, int y, int minY, int maxY, boolean waterOk) {
        if (y - 1 < minY || y + 1 >= maxY) {
            return false;
        }
        Kind floor = column.at(y - 1);
        Kind feet = column.at(y);
        Kind head = column.at(y + 1);
        return passable(feet, waterOk) && passable(head, waterOk)
                && (floor == Kind.SOLID || (waterOk && floor == Kind.WATER));
    }

    private static boolean passable(Kind kind, boolean waterOk) {
        return kind == Kind.EMPTY || (waterOk && kind == Kind.WATER);
    }

    /**
     * The safe spot closest to {@code startY}, checking {@code startY}, then alternately one block lower
     * and one block higher, up to {@code down} blocks below and {@code up} blocks above. Empty when there
     * is none (void, lava lake, solid rock).
     */
    static OptionalInt nearest(Column column, int startY, int minY, int maxY, int up, int down, boolean waterOk) {
        int range = Math.max(Math.max(up, down), 0);
        for (int d = 0; d <= range; d++) {
            if (d <= down && isSafe(column, startY - d, minY, maxY, waterOk)) {
                return OptionalInt.of(startY - d);
            }
            if (d > 0 && d <= up && isSafe(column, startY + d, minY, maxY, waterOk)) {
                return OptionalInt.of(startY + d);
            }
        }
        return OptionalInt.empty();
    }

    /** Highest safe spot in the column below {@code maxY} (for /top). */
    static OptionalInt highest(Column column, int minY, int maxY, boolean waterOk) {
        for (int y = maxY - 2; y > minY; y--) {
            if (isSafe(column, y, minY, maxY, waterOk)) {
                return OptionalInt.of(y);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Lowest safe spot at or below {@code startY} (for /down): the bottom of the cave or shaft the player
     * is standing over. Empty when the whole column below is unsafe (lava, void, solid rock).
     */
    static OptionalInt lowest(Column column, int startY, int minY, int maxY, boolean waterOk) {
        int top = Math.min(startY, maxY - 2);
        for (int y = minY + 1; y <= top; y++) {
            if (isSafe(column, y, minY, maxY, waterOk)) {
                return OptionalInt.of(y);
            }
        }
        return OptionalInt.empty();
    }
}
