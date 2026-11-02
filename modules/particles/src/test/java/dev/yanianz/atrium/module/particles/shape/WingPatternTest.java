package dev.yanianz.atrium.module.particles.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WingPatternTest {
    @Test
    void defaultPatternHasTwoSymmetricWings() {
        WingPattern pattern = WingPattern.defaults();
        assertEquals(7, pattern.rows().size());
        assertTrue(pattern.pointCount() > 20);
        List<WingPattern.Point> points = pattern.points(1.0);
        assertEquals(pattern.pointCount(), points.size());
        for (WingPattern.Point point : points) {
            assertTrue(Math.abs(point.x()) <= 1.0001, "x outside radius: " + point.x());
            assertTrue(point.y() >= 0.05 && point.y() <= 1.9, "y outside expected wing height");
            assertEquals(-0.25, point.z());
        }
    }

    @Test
    void spacesCountAsEmptyCells() {
        WingPattern pattern = WingPattern.parse(List.of("#.#", " # "));
        assertEquals(3, pattern.columns());
        assertEquals(3, pattern.pointCount());
    }

    @Test
    void emptyListFallsBackToDefault() {
        assertEquals(WingPattern.defaults().rows(), WingPattern.parse(List.of()).rows());
    }

    @Test
    void patternWithoutPointsIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> WingPattern.parse(List.of("...", "...")));
    }

    @Test
    void emptyConstructorIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new WingPattern(List.of()));
    }

    @Test
    void radiusScalesTheHalfWidth() {
        WingPattern pattern = WingPattern.parse(List.of("#####"));
        double narrow = pattern.points(1.0).stream().mapToDouble(p -> Math.abs(p.x())).max().orElseThrow();
        double wide = pattern.points(2.0).stream().mapToDouble(p -> Math.abs(p.x())).max().orElseThrow();
        assertEquals(2.0, wide / narrow, 1.0e-9);
    }

    @Test
    void oversizedRowsAreRejected() {
        String row = "#".repeat(WingPattern.MAX_COLUMNS + 1);
        assertThrows(IllegalArgumentException.class, () -> WingPattern.parse(List.of(row)));
    }
}
