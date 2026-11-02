package dev.yanianz.atrium.module.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Cell spread rules, without a server. */
class JailCellsTest {
    private static final List<JailCells.Cell> CELLS = List.of(
            new JailCells.Cell("world", 0, 64, 0, 0, 0),
            new JailCells.Cell("world", 10, 64, 0, 0, 0),
            new JailCells.Cell("world", 20, 64, 0, 0, 0));

    @Test
    void aJailWithoutCellsUsesItsSpawnPoint() {
        assertEquals(-1, JailCells.pick(List.of(), new int[0], JailCells.Strategy.LEAST_OCCUPIED, new Random(1)));
    }

    @Test
    void theLeastOccupiedCellWins() {
        int index = JailCells.pick(CELLS, new int[] {4, 0, 2}, JailCells.Strategy.LEAST_OCCUPIED, new Random(7));
        assertEquals(1, index, "the empty cell is picked");
    }

    @Test
    void anEmptyCellBeatsAnOccupiedOneWhenAllAreOccupied() {
        int index = JailCells.pick(CELLS, new int[] {3, 1, 1}, JailCells.Strategy.LEAST_OCCUPIED, new Random(2));
        assertTrue(index == 1 || index == 2, "one of the two cells with one prisoner: " + index);
    }

    @Test
    void missingOccupancyCountsAsEmpty() {
        int index = JailCells.pick(CELLS, new int[] {2}, JailCells.Strategy.LEAST_OCCUPIED, new Random(3));
        assertTrue(index == 1 || index == 2);
    }

    @Test
    void shuffleSpreadsPrisonersOverEveryCell() {
        boolean[] seen = new boolean[CELLS.size()];
        for (int seed = 0; seed < 200; seed++) {
            int index = JailCells.pick(CELLS, new int[] {9, 9, 9}, JailCells.Strategy.SHUFFLE, new Random(seed));
            assertTrue(index >= 0 && index < CELLS.size());
            seen[index] = true;
        }
        assertTrue(seen[0] && seen[1] && seen[2], "every cell is reachable");
    }

    @Test
    void strategyParsingIsForgiving() {
        assertEquals(JailCells.Strategy.SHUFFLE, JailCells.Strategy.parse("shuffle"));
        assertEquals(JailCells.Strategy.LEAST_OCCUPIED, JailCells.Strategy.parse("Least_Occupied"));
        assertEquals(JailCells.Strategy.LEAST_OCCUPIED, JailCells.Strategy.parse(null));
        assertEquals(JailCells.Strategy.LEAST_OCCUPIED, JailCells.Strategy.parse("nonsense"));
    }
}
