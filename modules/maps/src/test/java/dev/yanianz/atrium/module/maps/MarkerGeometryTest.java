package dev.yanianz.atrium.module.maps;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkerGeometryTest {
    private static MapMarker marker(String id, String world, double x, double z) {
        return new MapMarker(id, MapMarker.Category.WARP, world, x, 64, z, 0, id);
    }

    @Test void groupsMarkersByWorldAndSortsIds() {
        Map<String, List<MapMarker>> grouped = MarkerGeometry.byWorld(List.of(
                marker("b", "world_nether", 1, 1),
                marker("z", "world", 2, 2),
                marker("a", "world", 3, 3)));

        assertEquals(List.of("world", "world_nether"), List.copyOf(grouped.keySet()));
        assertEquals(List.of("a", "z"), grouped.get("world").stream().map(MapMarker::id).toList());
        assertEquals(List.of("b"), grouped.get("world_nether").stream().map(MapMarker::id).toList());
    }

    @Test void nullAndEmptyInputsProduceAnEmptyGrouping() {
        assertTrue(MarkerGeometry.byWorld(null).isEmpty());
        assertTrue(MarkerGeometry.byWorld(List.of()).isEmpty());
    }

    @Test void squareIsCentredAndSymmetric() {
        MarkerGeometry.Rect rect = MarkerGeometry.square(100, -40, 4);
        assertEquals(96, rect.minX());
        assertEquals(-44, rect.minZ());
        assertEquals(104, rect.maxX());
        assertEquals(-36, rect.maxZ());
    }

    @Test void aNonPositiveRadiusCollapsesTheSquare() {
        MarkerGeometry.Rect zero = MarkerGeometry.square(1.5, 2.5, 0);
        assertEquals(zero.minX(), zero.maxX());
        assertEquals(zero.minZ(), zero.maxZ());

        MarkerGeometry.Rect negative = MarkerGeometry.square(1.5, 2.5, -4);
        assertEquals(1.5, negative.minX());
        assertEquals(2.5, negative.maxZ());

        MarkerGeometry.Rect nan = MarkerGeometry.square(1.5, 2.5, Double.NaN);
        assertEquals(1.5, nan.maxX());
    }

    @Test void portalRangeBracketsTheOriginBlock() {
        MarkerGeometry.YRange range = MarkerGeometry.portalRange(70);
        assertEquals(69.0f, range.min());
        assertEquals(73.0f, range.max());
        assertTrue(range.max() > range.min());
    }
}
