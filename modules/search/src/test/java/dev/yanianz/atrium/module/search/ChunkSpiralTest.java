package dev.yanianz.atrium.module.search;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkSpiralTest {
    @Test void visitsEveryChunkOfTheSquareExactlyOnce() {
        for (int radius = 0; radius <= 6; radius++) {
            List<ChunkSpiral.Pos> order = ChunkSpiral.around(3, -2, radius);
            assertEquals(ChunkSpiral.count(radius), order.size());
            assertEquals(order.size(), new HashSet<>(order).size(), "no duplicates");
            for (ChunkSpiral.Pos pos : order) {
                assertTrue(Math.abs(pos.x() - 3) <= radius && Math.abs(pos.z() + 2) <= radius, pos.toString());
            }
        }
    }

    @Test void startsAtTheCentreAndNeverMovesBackInwards() {
        List<ChunkSpiral.Pos> order = ChunkSpiral.around(10, 20, 5);
        assertEquals(new ChunkSpiral.Pos(10, 20), order.getFirst());
        int previous = 0;
        for (ChunkSpiral.Pos pos : order) {
            int ring = Math.max(Math.abs(pos.x() - 10), Math.abs(pos.z() - 20));
            assertTrue(ring >= previous, "rings must not go back inwards");
            previous = ring;
        }
        assertEquals(5, previous);
    }

    @Test void coversAnExactThreeByThreeBoxAroundTheCentre() {
        List<ChunkSpiral.Pos> order = ChunkSpiral.around(0, 0, 1);
        assertEquals(9, order.size());
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                int finalX = x;
                int finalZ = z;
                assertTrue(order.contains(new ChunkSpiral.Pos(finalX, finalZ)));
            }
        }
    }

    @Test void rejectsNegativeRadius() {
        assertThrows(IllegalArgumentException.class, () -> ChunkSpiral.around(0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> ChunkSpiral.count(-1));
    }
}
