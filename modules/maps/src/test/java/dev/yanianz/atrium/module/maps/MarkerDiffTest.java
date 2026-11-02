package dev.yanianz.atrium.module.maps;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkerDiffTest {
    private static MapMarker marker(String id, double x, String label) {
        return new MapMarker(id, MapMarker.Category.WARP, "world", x, 64, 0, 0, label);
    }

    private static Map<String, MapMarker> index(MapMarker... markers) {
        Map<String, MapMarker> map = new LinkedHashMap<>();
        for (MapMarker marker : markers) {
            map.put(marker.id(), marker);
        }
        return map;
    }

    @Test void identicalSetsProduceNoChanges() {
        MarkerDiff diff = MarkerDiff.diff(index(marker("a", 1, "A"), marker("b", 2, "B")),
                index(marker("a", 1, "A"), marker("b", 2, "B")));
        assertTrue(diff.isEmpty());
        assertEquals(0, diff.changeCount());
    }

    @Test void reportsAddedRemovedAndChangedMarkers() {
        MarkerDiff diff = MarkerDiff.diff(
                index(marker("kept", 1, "Kept"), marker("gone", 2, "Gone"), marker("moved", 3, "Moved")),
                index(marker("kept", 1, "Kept"), marker("new", 4, "New"), marker("moved", 30, "Moved")));
        assertEquals(List.of("new"), diff.added().stream().map(MapMarker::id).toList());
        assertEquals(List.of("moved"), diff.changed().stream().map(MapMarker::id).toList());
        assertEquals(List.of("gone"), diff.removed());
        assertEquals(3, diff.changeCount());
    }

    @Test void aRenameWithTheSameIdCountsAsAChange() {
        MarkerDiff diff = MarkerDiff.diff(index(marker("warp_1", 1, "Old")),
                index(marker("warp_1", 1, "New")));
        assertTrue(diff.added().isEmpty());
        assertTrue(diff.removed().isEmpty());
        assertEquals(List.of("warp_1"), diff.changed().stream().map(MapMarker::id).toList());
    }

    @Test void changedAndRemovedOutputIsSortedForStableLogs() {
        MarkerDiff diff = MarkerDiff.diff(
                index(marker("z", 1, "Z"), marker("a", 1, "A"), marker("m", 1, "M")),
                index(marker("z", 2, "Z"), marker("a", 2, "A"), marker("m", 2, "M")));
        assertEquals(List.of("a", "m", "z"), diff.changed().stream().map(MapMarker::id).toList());

        MarkerDiff removed = MarkerDiff.diff(index(marker("z", 1, "Z"), marker("a", 1, "A")), Map.of());
        assertEquals(List.of("a", "z"), removed.removed());
    }

    @Test void handlesNullAndEmptyInputs() {
        assertTrue(MarkerDiff.diff(null, null).isEmpty());
        assertTrue(MarkerDiff.diff(null, Map.of()).isEmpty());
        assertTrue(MarkerDiff.diff(Map.of(), null).isEmpty());
        assertEquals(1, MarkerDiff.diff(null, index(marker("a", 1, "A"))).added().size());
    }

    @Test void indexKeepsTheLastMarkerForADuplicateId() {
        Map<String, MapMarker> indexed = MarkerDiff.index(List.of(marker("a", 1, "First"), marker("a", 2, "Second")));
        assertEquals(1, indexed.size());
        assertEquals("Second", indexed.get("a").label());
    }

    @Test void anEmptyToNonEmptySetIsOnlyAdditions() {
        MarkerDiff diff = MarkerDiff.diff(Map.of(), index(marker("a", 1, "A"), marker("b", 2, "B")));
        assertFalse(diff.added().isEmpty());
        assertEquals(2, diff.added().size());
        assertTrue(diff.changed().isEmpty());
        assertTrue(diff.removed().isEmpty());
    }
}
