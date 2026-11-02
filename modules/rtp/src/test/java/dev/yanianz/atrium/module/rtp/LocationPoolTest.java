package dev.yanianz.atrium.module.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LocationPoolTest {
    private static LocationPool.Spot spot(int x, long at) {
        return new LocationPool.Spot(x, 64, 0, 0f, at);
    }

    @Test
    void capacityPerWorldAndOldestFirst() {
        LocationPool pool = new LocationPool();
        assertTrue(pool.offer("world", spot(1, 0), 2));
        assertTrue(pool.offer("world", spot(2, 0), 2));
        assertFalse(pool.offer("world", spot(3, 0), 2));
        assertTrue(pool.offer("nether", spot(9, 0), 2));
        assertEquals(2, pool.size("world"));
        assertEquals(1, pool.poll("world", 10, 1000).orElseThrow().x());
        assertEquals(2, pool.poll("world", 10, 1000).orElseThrow().x());
        assertTrue(pool.poll("world", 10, 1000).isEmpty());
        assertTrue(pool.poll("unknown", 10, 1000).isEmpty());
    }

    @Test
    void staleSpotsAreDropped() {
        LocationPool pool = new LocationPool();
        pool.offer("world", spot(1, 0), 5);
        pool.offer("world", spot(2, 500), 5);
        assertEquals(2, pool.poll("world", 1200, 1000).orElseThrow().x());
        pool.offer("world", spot(3, 0), 5);
        pool.offer("world", spot(4, 900), 5);
        pool.expire(1200, 1000);
        assertEquals(1, pool.size("world"));
        pool.clear();
        assertEquals(0, pool.size("world"));
    }
}
