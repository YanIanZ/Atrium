package dev.yanianz.atrium.module.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.yanianz.atrium.module.rtp.ColumnScan.Kind;
import java.util.Arrays;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class ColumnScanTest {
    private static final int MIN = 0;
    private static final int TOP = 128;

    private static final class Grid implements ColumnScan.Column {
        final Kind[] blocks = new Kind[TOP - MIN];

        Grid() {
            Arrays.fill(blocks, Kind.EMPTY);
        }

        Grid fill(int from, int to, Kind kind) {
            for (int y = from; y <= to; y++) {
                blocks[y - MIN] = kind;
            }
            return this;
        }

        @Override
        public Kind at(int y) {
            if (y < MIN || y >= TOP) {
                throw new AssertionError("read outside the column: " + y);
            }
            return blocks[y - MIN];
        }
    }

    @Test
    void landsOnTheSurface() {
        Grid grid = new Grid().fill(0, 63, Kind.SOLID);
        assertEquals(OptionalInt.of(64), ColumnScan.surface(grid, 64, MIN, TOP));
        // A start above the surface walks down to it.
        assertEquals(OptionalInt.of(64), ColumnScan.surface(grid, 100, MIN, TOP));
    }

    @Test
    void rejectedSurfaceIsNotTradedForACave() {
        // Lava (or avoided water) surface with a dry cave below: refuse the column.
        Grid grid = new Grid().fill(0, 39, Kind.SOLID).fill(42, 59, Kind.SOLID).fill(60, 62, Kind.AVOID);
        assertEquals(OptionalInt.empty(), ColumnScan.surface(grid, 70, MIN, TOP));
    }

    @Test
    void allowedWaterFloats() {
        Grid grid = new Grid().fill(0, 50, Kind.SOLID).fill(51, 62, Kind.WATER);
        assertEquals(OptionalInt.of(63), ColumnScan.surface(grid, 63, MIN, TOP));
    }

    @Test
    void voidColumnHasNoSpot() {
        assertEquals(OptionalInt.empty(), ColumnScan.surface(new Grid(), MIN, MIN, TOP));
        assertEquals(OptionalInt.empty(), ColumnScan.surface(new Grid(), 100, MIN, TOP));
    }

    @Test
    void surfaceNeedsHeadroomBelowTheCeiling() {
        Grid grid = new Grid().fill(0, 126, Kind.SOLID);
        assertEquals(OptionalInt.empty(), ColumnScan.surface(grid, 127, MIN, TOP));
    }

    @Test
    void netherSearchStartsBelowTheBedrockRoof() {
        // Ground up to 63, open cave 64..119, netherrack 120..122, bedrock roof 123..127 with a pocket at 125.
        Grid grid = new Grid().fill(0, 63, Kind.SOLID).fill(120, 122, Kind.SOLID).fill(123, 127, Kind.AVOID);
        grid.blocks[125] = Kind.EMPTY;
        assertEquals(OptionalInt.of(64), ColumnScan.belowRoof(grid, MIN, TOP));
    }

    @Test
    void roofPocketDirectlyUnderBedrockIsSkipped() {
        // 2-high pocket at 121..122 right under the bedrock (123): head would touch the roof; ground at 64 wins.
        Grid grid = new Grid().fill(0, 63, Kind.SOLID).fill(110, 120, Kind.SOLID).fill(123, 127, Kind.AVOID);
        assertEquals(OptionalInt.of(64), ColumnScan.belowRoof(grid, MIN, TOP));
    }

    @Test
    void netherLavaSeaIsNotAFloor() {
        // Roof, open air, then a lava sea down to the bottom: no spot.
        Grid grid = new Grid().fill(0, 31, Kind.AVOID).fill(123, 127, Kind.AVOID);
        assertEquals(OptionalInt.empty(), ColumnScan.belowRoof(grid, MIN, TOP));
        // A ledge above the lava is fine.
        grid.fill(50, 50, Kind.SOLID);
        assertEquals(OptionalInt.of(51), ColumnScan.belowRoof(grid, MIN, TOP));
    }
}
