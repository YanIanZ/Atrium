package dev.yanianz.atrium.module.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.module.teleport.Spiral.Offset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SpiralTest {
    @Test
    void startsAtTargetThenDirectNeighboursThenDiagonals() {
        List<Offset> order = Spiral.offsets(1);
        assertEquals(new Offset(0, 0), order.get(0));
        // north, east, south, west (clockwise from north), then the diagonals clockwise from north-east
        assertEquals(List.of(new Offset(0, -1), new Offset(1, 0), new Offset(0, 1), new Offset(-1, 0)), order.subList(1, 5));
        assertEquals(List.of(new Offset(1, -1), new Offset(1, 1), new Offset(-1, 1), new Offset(-1, -1)), order.subList(5, 9));
    }

    @Test
    void everyColumnOnceAndRingsNeverGoBackwards() {
        for (int r = 0; r <= Spiral.MAX_RADIUS; r++) {
            List<Offset> order = Spiral.offsets(r);
            assertEquals((2 * r + 1) * (2 * r + 1), order.size());
            assertEquals(order.size(), new HashSet<>(order).size());
            for (int i = 1; i < order.size(); i++) {
                Offset a = order.get(i - 1);
                Offset b = order.get(i);
                assertTrue(a.ring() <= b.ring(), "ring order at " + i);
                if (a.ring() == b.ring()) {
                    assertTrue(a.distanceSquared() <= b.distanceSquared(), "distance order at " + i);
                }
                assertTrue(b.ring() <= r);
            }
        }
    }

    @Test
    void radiusIsClamped() {
        assertEquals(1, Spiral.offsets(-3).size());
        assertEquals(Spiral.offsets(Spiral.MAX_RADIUS), Spiral.offsets(100));
    }

    @Test
    void runsGroupConsecutiveColumnsOfTheSameChunk() {
        // Column 15,15 is the corner of chunk 0,0: neighbours spill into three other chunks.
        List<List<Offset>> runs = Spiral.runsByChunk(15, 15, 2);
        Set<Offset> seen = new HashSet<>();
        int total = 0;
        for (List<Offset> run : runs) {
            int cx = (15 + run.getFirst().dx()) >> 4;
            int cz = (15 + run.getFirst().dz()) >> 4;
            for (Offset o : run) {
                assertEquals(cx, (15 + o.dx()) >> 4);
                assertEquals(cz, (15 + o.dz()) >> 4);
                assertTrue(seen.add(o));
                total++;
            }
        }
        assertEquals(24, total);
        // Flattening the runs gives back the spiral order without the centre.
        assertEquals(Spiral.offsets(2).subList(1, 25), runs.stream().flatMap(List::stream).toList());
        // Inside a chunk everything is one run.
        assertEquals(1, Spiral.runsByChunk(8, 8, 2).size());
    }
}
