package dev.yanianz.atrium.module.worldtools;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Pure selection geometry used by the wand, /select and /replaceblock selection. */
class CuboidTest {
    private static Cuboid.Point point(String world, int x, int y, int z) {
        return new Cuboid.Point(world, x, y, z);
    }

    @Test void cornersAreOrderedRegardlessOfClickOrder() {
        Cuboid a = Cuboid.of(point("world", 10, 70, -5), point("world", -2, 60, 8));
        Cuboid b = Cuboid.of(point("world", -2, 60, 8), point("world", 10, 70, -5));
        assertEquals(a, b);
        assertEquals(-2, a.minX());
        assertEquals(10, a.maxX());
        assertEquals(60, a.minY());
        assertEquals(70, a.maxY());
        assertEquals(-5, a.minZ());
        assertEquals(8, a.maxZ());
    }

    @Test void rejectsCornersFromDifferentWorlds() {
        assertThrows(IllegalArgumentException.class,
                () -> Cuboid.of(point("world", 0, 0, 0), point("world_nether", 1, 1, 1)));
        assertFalse(Cuboid.sameWorld(point("a", 0, 0, 0), point("b", 0, 0, 0)));
    }

    @Test void volumeCountsEveryBlockInclusive() {
        Cuboid cuboid = Cuboid.of(point("world", 0, 0, 0), point("world", 1, 1, 1));
        assertEquals(8, cuboid.volume());
        assertTrue(cuboid.contains(0, 0, 0));
        assertTrue(cuboid.contains(1, 1, 1));
        assertFalse(cuboid.contains(2, 1, 1));
        assertFalse(cuboid.contains(1, -1, 1));
    }

    @Test void expandsTowardsTheRequestedSide() {
        Cuboid cuboid = Cuboid.of(point("world", 0, 64, 0), point("world", 0, 64, 0));
        assertEquals(-2, cuboid.expand(Cuboid.Direction.WEST, 2, -64, 319).minX());
        assertEquals(2, cuboid.expand(Cuboid.Direction.EAST, 2, -64, 319).maxX());
        assertEquals(-2, cuboid.expand(Cuboid.Direction.NORTH, 2, -64, 319).minZ());
        assertEquals(2, cuboid.expand(Cuboid.Direction.SOUTH, 2, -64, 319).maxZ());
        assertEquals(66, cuboid.expand(Cuboid.Direction.UP, 2, -64, 319).maxY());
        assertEquals(62, cuboid.expand(Cuboid.Direction.DOWN, 2, -64, 319).minY());
        Cuboid vertical = cuboid.expand(Cuboid.Direction.VERT, 1, -64, 319);
        assertEquals(-64, vertical.minY());
        assertEquals(319, vertical.maxY());
    }

    @Test void expansionClampsToBuildHeight() {
        Cuboid cuboid = Cuboid.of(point("world", 0, 300, 0), point("world", 0, 310, 0));
        assertEquals(319, cuboid.expand(Cuboid.Direction.UP, 100, -64, 319).maxY());
        Cuboid low = Cuboid.of(point("world", 0, -50, 0), point("world", 0, -40, 0));
        assertEquals(-64, low.expand(Cuboid.Direction.DOWN, 100, -64, 319).minY());
    }

    @Test void limitIsInclusiveAndNeverAboveTheHardLimit() {
        Cuboid cuboid = Cuboid.of(point("world", 0, 0, 0), point("world", 9, 9, 9));
        assertEquals(1000, cuboid.volume());
        assertTrue(cuboid.fits(1000));
        assertFalse(cuboid.fits(999));
        // 0 or a negative limit means "the hard limit", never "unlimited".
        assertFalse(Cuboid.of(point("world", 0, 0, 0), point("world", 256, 256, 256)).fits(0));
        // Exactly the hard limit still fits.
        assertTrue(Cuboid.of(point("world", 0, 0, 0), point("world", 255, 255, 255)).fits(0));
    }

    @Test void facingTurnsAimIntoADirection() {
        assertEquals(Cuboid.Direction.SOUTH, Cuboid.facing(0f, 0f));
        assertEquals(Cuboid.Direction.WEST, Cuboid.facing(90f, 0f));
        assertEquals(Cuboid.Direction.NORTH, Cuboid.facing(180f, 0f));
        assertEquals(Cuboid.Direction.EAST, Cuboid.facing(270f, 0f));
        assertEquals(Cuboid.Direction.SOUTH, Cuboid.facing(-45f, 0f));
        assertEquals(Cuboid.Direction.WEST, Cuboid.facing(-360f + 90f, 0f));
        assertEquals(Cuboid.Direction.UP, Cuboid.facing(0f, -60f));
        assertEquals(Cuboid.Direction.DOWN, Cuboid.facing(0f, 60f));
        // A shallow look stays horizontal.
        assertEquals(Cuboid.Direction.NORTH, Cuboid.facing(180f, 20f));
    }

    @Test void directionParsingIsForgiving() {
        assertEquals(Cuboid.Direction.VERT, Cuboid.Direction.parse(" vertical "));
        assertEquals(Cuboid.Direction.UP, Cuboid.Direction.parse("UP"));
        assertNull(Cuboid.Direction.parse("sideways"));
        assertNull(Cuboid.Direction.parse(null));
    }

    @Test void describeReportsTheVolume() {
        Cuboid cuboid = Cuboid.of(point("world", 1, 2, 3), point("world", 1, 2, 3));
        assertEquals("1,2,3 -> 1,2,3 (1)", cuboid.describe());
    }
}
