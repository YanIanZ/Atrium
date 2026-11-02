package dev.yanianz.atrium.module.portals;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class CuboidTest {
    private final Cuboid box = Cuboid.of("world", 5, 70, -3, 2, 64, -1);

    @Test void normalisesCornersInAnyOrder() {
        assertEquals(new Cuboid("world", 2, 64, -3, 5, 70, -1), box);
        assertEquals(4, box.sizeX());
        assertEquals(7, box.sizeY());
        assertEquals(3, box.sizeZ());
        assertEquals(84, box.volume());
        assertEquals(7, box.maxSide());
    }

    @Test void containmentIsInclusiveOfBlocksAndExclusiveOfTheFarFace() {
        assertTrue(box.containsBlock(2, 64, -3));
        assertTrue(box.containsBlock(5, 70, -1));
        assertFalse(box.containsBlock(6, 70, -1));
        assertTrue(box.contains(2.0, 64.0, -3.0));
        assertTrue(box.contains(5.999, 70.999, -0.001));
        assertFalse(box.contains(6.0, 65, -2));
        assertFalse(box.contains(3, 65, 0.0));
        assertFalse(box.contains(1.999, 65, -2));
        // Negative coordinates: block -3 spans [-3, -2).
        assertTrue(box.contains(3, 65, -2.5));
        assertFalse(box.contains(3, 65, -3.0001));
    }

    @Test void segmentTestCatchesMovementThatJumpsOverAThinPortal() {
        Cuboid wall = Cuboid.of("world", 10, 64, 0, 10, 66, 5); // one block thick at x = 10
        assertTrue(wall.intersectsSegment(8.5, 64.5, 2.5, 12.5, 64.5, 2.5));
        assertFalse(wall.intersectsSegment(8.5, 64.5, 2.5, 9.9, 64.5, 2.5));
        assertFalse(wall.intersectsSegment(8.5, 70, 2.5, 12.5, 70, 2.5));
        assertTrue(wall.intersectsSegment(10.5, 65, 3, 10.5, 65, 3)); // standing still inside
        assertFalse(wall.intersectsSegment(11, 65, 3, 11, 65, 3)); // far face is outside
        assertTrue(wall.intersectsSegment(12.5, 66.9, 7, 8.5, 64.1, -1));
    }

    @Test void overlapNeedsSameWorldAndSharedBlocks() {
        assertTrue(box.overlaps(Cuboid.of("world", 5, 70, -1, 9, 80, 9)));
        assertFalse(box.overlaps(Cuboid.of("world", 6, 70, -1, 9, 80, 9)));
        assertFalse(box.overlaps(Cuboid.of("nether", 2, 64, -3, 5, 70, -1)));
    }

    @Test void chunkRangeUsesFloorDivisionForNegatives() {
        Cuboid c = Cuboid.of("w", -1, 0, -17, 16, 0, 15);
        assertEquals(-1, c.minChunkX());
        assertEquals(1, c.maxChunkX());
        assertEquals(-2, c.minChunkZ());
        assertEquals(0, c.maxChunkZ());
    }

    @Test void distanceToBox() {
        assertEquals(0.0, box.distanceSquared(3, 65, -2));
        assertEquals(4.0, box.distanceSquared(8, 65, -2), 1e-9);
    }

    @Test void describeIsLocaleIndependent() {
        Locale old = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("id-ID"));
            assertEquals("world 2,64,-3 -> 5,70,-1", box.describe());
        } finally {
            Locale.setDefault(old);
        }
    }

    @Test void rejectsInvertedCorners() {
        assertThrows(IllegalArgumentException.class, () -> new Cuboid("w", 1, 0, 0, 0, 0, 0));
    }
}
