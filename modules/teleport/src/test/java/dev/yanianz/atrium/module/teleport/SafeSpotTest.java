package dev.yanianz.atrium.module.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.module.teleport.SafeSpot.Kind;
import java.util.Arrays;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class SafeSpotTest {
    private static final int MIN = 0;
    private static final int MAX = 64;

    /** A column of air with the given blocks set; y outside [MIN, MAX) fails the test. */
    private static final class Grid implements SafeSpot.Column {
        final Kind[] blocks = new Kind[MAX - MIN];

        Grid() {
            Arrays.fill(blocks, Kind.EMPTY);
        }

        Grid set(int y, Kind kind) {
            blocks[y - MIN] = kind;
            return this;
        }

        Grid fill(int from, int to, Kind kind) {
            for (int y = from; y <= to; y++) {
                set(y, kind);
            }
            return this;
        }

        @Override
        public Kind at(int y) {
            if (y < MIN || y >= MAX) {
                throw new AssertionError("read outside the column: " + y);
            }
            return blocks[y - MIN];
        }
    }

    @Test
    void standingOnGroundIsSafe() {
        Grid grid = new Grid().fill(0, 9, Kind.SOLID);
        assertTrue(SafeSpot.isSafe(grid, 10, MIN, MAX, false));
        assertEquals(OptionalInt.of(10), SafeSpot.nearest(grid, 10, MIN, MAX, 5, 5, false));
    }

    @Test
    void insideBlocksMovesUpToTheSurface() {
        Grid grid = new Grid().fill(0, 20, Kind.SOLID);
        assertFalse(SafeSpot.isSafe(grid, 18, MIN, MAX, false));
        assertEquals(OptionalInt.of(21), SafeSpot.nearest(grid, 18, MIN, MAX, 5, 5, false));
    }

    @Test
    void inTheAirFallsBackToTheGround() {
        Grid grid = new Grid().fill(0, 9, Kind.SOLID);
        assertEquals(OptionalInt.of(10), SafeSpot.nearest(grid, 14, MIN, MAX, 5, 5, false));
    }

    @Test
    void voidBelowWithoutFloorInRangeIsUnsafe() {
        Grid grid = new Grid();
        assertEquals(OptionalInt.empty(), SafeSpot.nearest(grid, 30, MIN, MAX, 10, 10, true));
    }

    @Test
    void floorTooFarBelowIsUnsafe() {
        Grid grid = new Grid().fill(0, 4, Kind.SOLID);
        assertEquals(OptionalInt.empty(), SafeSpot.nearest(grid, 30, MIN, MAX, 3, 10, false));
        assertEquals(OptionalInt.of(5), SafeSpot.nearest(grid, 30, MIN, MAX, 3, 25, false));
    }

    @Test
    void lavaIsNeverAFloorOrAroundThePlayer() {
        Grid lavaFloor = new Grid().fill(0, 8, Kind.SOLID).set(9, Kind.HAZARD);
        assertFalse(SafeSpot.isSafe(lavaFloor, 10, MIN, MAX, true));
        Grid lavaAtFeet = new Grid().fill(0, 9, Kind.SOLID).set(10, Kind.HAZARD);
        assertFalse(SafeSpot.isSafe(lavaAtFeet, 10, MIN, MAX, true));
        Grid fireAtHead = new Grid().fill(0, 9, Kind.SOLID).set(11, Kind.HAZARD);
        assertFalse(SafeSpot.isSafe(fireAtHead, 10, MIN, MAX, true));
    }

    @Test
    void lavaLakeIsUnsafeEvenWithLargeRange() {
        Grid grid = new Grid().fill(0, 5, Kind.SOLID).fill(6, 20, Kind.HAZARD);
        // Above the lava there is only air: no floor until the lava, which is not a floor.
        assertEquals(OptionalInt.empty(), SafeSpot.nearest(grid, 21, MIN, MAX, 10, 30, true));
    }

    @Test
    void waterDependsOnTheSetting() {
        Grid grid = new Grid().fill(0, 5, Kind.SOLID).fill(6, 12, Kind.WATER);
        assertTrue(SafeSpot.isSafe(grid, 13, MIN, MAX, true));
        assertFalse(SafeSpot.isSafe(grid, 13, MIN, MAX, false));
        // Without water allowed the nearest spot is not reachable within range.
        assertEquals(OptionalInt.empty(), SafeSpot.nearest(grid, 13, MIN, MAX, 2, 2, false));
    }

    @Test
    void needsTwoFreeBlocks() {
        Grid grid = new Grid().fill(0, 9, Kind.SOLID).set(11, Kind.SOLID);
        assertFalse(SafeSpot.isSafe(grid, 10, MIN, MAX, false));
        assertEquals(OptionalInt.of(12), SafeSpot.nearest(grid, 10, MIN, MAX, 5, 5, false));
    }

    @Test
    void prefersTheClosestSpot() {
        // Floors at 9 (spot 10) and at 14 (spot 15, inside a 2-high gap below a ceiling at 17).
        Grid grid = new Grid().fill(0, 9, Kind.SOLID).set(14, Kind.SOLID).set(17, Kind.SOLID);
        assertEquals(OptionalInt.of(15), SafeSpot.nearest(grid, 14, MIN, MAX, 5, 5, false));
        assertEquals(OptionalInt.of(10), SafeSpot.nearest(grid, 12, MIN, MAX, 5, 5, false));
    }

    @Test
    void neverReadsOutsideTheWorld() {
        Grid grid = new Grid().fill(0, 62, Kind.SOLID);
        // Head would be at MAX: not safe, and no read at MAX happens.
        assertFalse(SafeSpot.isSafe(grid, MAX - 1, MIN, MAX, false));
        assertEquals(OptionalInt.empty(), SafeSpot.nearest(grid, MAX - 2, MIN, MAX, 10, 0, false));
        assertFalse(SafeSpot.isSafe(grid, MIN, MIN, MAX, false));
        assertEquals(OptionalInt.empty(), SafeSpot.nearest(new Grid(), 1, MIN, MAX, 0, 10, false));
    }

    @Test
    void ceilingLimitsTheSearch() {
        // A "nether roof": open air above y=40 but the ceiling is 32.
        Grid grid = new Grid().fill(0, 39, Kind.SOLID);
        assertEquals(OptionalInt.empty(), SafeSpot.nearest(grid, 30, MIN, 32, 20, 20, false));
        assertEquals(OptionalInt.of(40), SafeSpot.nearest(grid, 30, MIN, MAX, 20, 20, false));
    }

    @Test
    void highestFindsTheTopSurface() {
        Grid grid = new Grid().fill(0, 9, Kind.SOLID).fill(20, 30, Kind.SOLID);
        assertEquals(OptionalInt.of(31), SafeSpot.highest(grid, MIN, MAX, false));
        assertEquals(OptionalInt.of(10), SafeSpot.highest(grid, MIN, 25, false));
        assertEquals(OptionalInt.empty(), SafeSpot.highest(new Grid(), MIN, MAX, false));
    }
}
