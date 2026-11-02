package dev.yanianz.atrium.module.portals;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PortalIndexTest {
    private static Portal portal(String name, Cuboid area) {
        return new Portal(name, area, PortalTarget.NONE, null, null, null, 0L);
    }

    @Test void chunkKeysAreUniqueIncludingNegatives() {
        Set<Long> keys = new HashSet<>();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                assertTrue(keys.add(PortalIndex.chunkKey(x, z)), x + "," + z);
            }
        }
        assertNotEquals(PortalIndex.chunkKey(0, -1), PortalIndex.chunkKey(-1, 0));
        assertEquals(-1, PortalIndex.chunkOf(-0.5));
        assertEquals(-1, PortalIndex.chunkOf(-16.0));
        assertEquals(-2, PortalIndex.chunkOf(-16.01));
        assertEquals(0, PortalIndex.chunkOf(15.99));
    }

    @Test void portalIsBucketedInEveryChunkItTouchesOnly() {
        Portal wide = portal("wide", Cuboid.of("world", -5, 60, 10, 20, 70, 12)); // chunks x -1..1, z 0
        PortalIndex index = PortalIndex.build(List.of(wide));
        assertEquals(3, index.bucketCount("world"));
        assertEquals(List.of(wide), index.bucket("world", -1, 0));
        assertEquals(List.of(wide), index.bucket("world", 1, 0));
        assertTrue(index.bucket("world", 2, 0).isEmpty());
        assertTrue(index.bucket("world", 0, 1).isEmpty());
        assertTrue(index.bucket("nether", 0, 0).isEmpty());
    }

    @Test void containingOnlyLooksAtTheBucketAndChecksTheBox() {
        Portal a = portal("a", Cuboid.of("world", 0, 64, 0, 2, 66, 0));
        Portal b = portal("b", Cuboid.of("world", 4, 64, 4, 6, 66, 6));
        Portal far = portal("far", Cuboid.of("world", 1000, 64, 1000, 1002, 66, 1002));
        Portal other = portal("other", Cuboid.of("nether", 0, 64, 0, 2, 66, 0));
        PortalIndex index = PortalIndex.build(List.of(far, b, other, a));
        assertEquals(List.of(a), index.containing("world", 1.5, 64.2, 0.5));
        assertEquals(List.of(b), index.containing("world", 5, 65, 5));
        assertTrue(index.containing("world", 3, 65, 3).isEmpty());
        assertEquals(List.of(other), index.containing("nether", 1, 65, 0.5));
        assertEquals(List.of(a, b, far), index.inWorld("world"));
        assertTrue(index.hasWorld("nether"));
        assertFalse(index.hasWorld("the_end"));
    }

    @Test void crossedByFindsPortalsBetweenTwoMoveSamplesAcrossChunks() {
        Portal thin = portal("thin", Cuboid.of("world", 16, 64, 0, 16, 67, 3)); // first block of chunk x = 1
        PortalIndex index = PortalIndex.build(List.of(thin));
        // Fast elytra move from chunk 0 to chunk 1, skipping the one-block-thick portal.
        assertEquals(List.of(thin), index.crossedBy("world", 14.5, 65, 1.5, 18.5, 65, 1.5));
        assertTrue(index.crossedBy("world", 14.5, 70, 1.5, 18.5, 70, 1.5).isEmpty());
        // A jump across very many chunks only checks the end point.
        assertTrue(index.crossedBy("world", -500, 65, 1.5, 18.5, 65, 1.5).isEmpty());
        assertEquals(List.of(thin), index.crossedBy("world", -500, 65, 1.5, 16.5, 65, 1.5));
    }

    @Test void emptyIndex() {
        PortalIndex index = PortalIndex.build(List.of());
        assertTrue(index.isEmpty());
        assertTrue(index.containing("world", 0, 0, 0).isEmpty());
        assertTrue(index.crossedBy("world", 0, 0, 0, 1, 1, 1).isEmpty());
    }
}
