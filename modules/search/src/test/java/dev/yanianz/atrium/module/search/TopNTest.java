package dev.yanianz.atrium.module.search;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TopNTest {
    @Test void sortsByCountThenByKey() {
        List<TopN.Row> rows = TopN.top(Map.of(
                "world:1:1", 5,
                "world:2:2", 9,
                "world:0:0", 5), 10);
        assertEquals(List.of(
                new TopN.Row("world:2:2", 9),
                new TopN.Row("world:0:0", 5),
                new TopN.Row("world:1:1", 5)), rows);
    }

    @Test void limitsTheSelectionAndDropsEmptyEntries() {
        List<TopN.Row> rows = TopN.top(Map.of(
                "a", 3,
                "b", 2,
                "c", 0,
                "d", 1), 2);
        assertEquals(List.of(new TopN.Row("a", 3), new TopN.Row("b", 2)), rows);
        assertTrue(TopN.top(Map.of("a", 3), 0).isEmpty());
        assertTrue(TopN.top(Map.of(), 5).isEmpty());
    }

    @Test void isStableAcrossRuns() {
        Map<String, Integer> counts = Map.of("z", 4, "y", 4, "x", 4);
        assertEquals(List.of(new TopN.Row("x", 4), new TopN.Row("y", 4), new TopN.Row("z", 4)),
                TopN.top(counts, 3));
    }
}
