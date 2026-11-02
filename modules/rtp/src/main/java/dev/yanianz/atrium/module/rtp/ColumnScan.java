package dev.yanianz.atrium.module.rtp;

import java.util.OptionalInt;

/**
 * Pure landing search over one block column. A player standing at {@code y} has the floor at {@code y - 1},
 * feet at {@code y} and head at {@code y + 1}. Unit tested with fake columns; {@link RtpSearcher} feeds it
 * real blocks on the region thread.
 */
final class ColumnScan {
    /** What a block means for landing, already resolved against the world's settings. */
    enum Kind {
        /** No collision and harmless (air, grass, flowers, snow layer...). */
        EMPTY,
        /** Can be stood on. */
        SOLID,
        /** Water when the world allows landing on water (otherwise water is {@link #AVOID}). */
        WATER,
        /** Never land on or in it: lava, magma, cactus, powder snow, leaves when avoided, bedrock roof... */
        AVOID
    }

    @FunctionalInterface
    interface Column {
        Kind at(int y);
    }

    private ColumnScan() {}

    /**
     * Open-sky worlds: walks down from {@code startY} to the first block below an empty one (the surface) and
     * accepts it only when it is solid (or allowed water) with two free blocks above. A rejected surface is
     * never traded for a cave below it.
     *
     * @param ceiling exclusive upper bound of readable blocks
     */
    static OptionalInt surface(Column column, int startY, int minY, int ceiling) {
        int top = Math.min(startY, ceiling - 2);
        for (int y = top; y > minY; y--) {
            Kind floor = column.at(y - 1);
            if (floor == Kind.EMPTY) {
                continue;
            }
            boolean room = column.at(y) == Kind.EMPTY && column.at(y + 1) == Kind.EMPTY;
            return room && (floor == Kind.SOLID || floor == Kind.WATER) ? OptionalInt.of(y) : OptionalInt.empty();
        }
        return OptionalInt.empty();
    }

    /**
     * Worlds with a ceiling (the Nether): starts at the top, passes through the roof until the first open block
     * below it, then takes the highest spot with a solid floor, two free blocks and no hazard directly above
     * the head (no pocket inside the bedrock roof, no lava dripping in).
     *
     * @param ceiling exclusive top of the usable height (the logical height)
     */
    static OptionalInt belowRoof(Column column, int minY, int ceiling) {
        int y = ceiling - 1;
        while (y > minY && column.at(y) != Kind.EMPTY) {
            y--;
        }
        for (; y > minY; y--) {
            if (y + 1 >= ceiling) {
                continue;
            }
            if (column.at(y) != Kind.EMPTY || column.at(y + 1) != Kind.EMPTY || column.at(y - 1) != Kind.SOLID) {
                continue;
            }
            if (y + 2 < ceiling && column.at(y + 2) == Kind.AVOID) {
                continue;
            }
            return OptionalInt.of(y);
        }
        return OptionalInt.empty();
    }
}
