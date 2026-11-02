package dev.yanianz.atrium.module.effects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ShapesTest {
    private static final double DELTA = 1e-9;

    @Test void everyShapeReturnsTheRequestedNumberOfFiniteOffsets() {
        for (Shape shape : Shape.values()) {
            List<Shapes.Offset> frame = Shapes.frame(shape, 2.0, 3.0, 32, 15.0, 4);
            assertEquals(32, frame.size(), shape.name());
            for (Shapes.Offset offset : frame) {
                assertTrue(Double.isFinite(offset.x()), shape.name());
                assertTrue(Double.isFinite(offset.y()), shape.name());
                assertTrue(Double.isFinite(offset.z()), shape.name());
                assertTrue(offset.y() >= 0, shape.name() + " must stay above the origin");
            }
        }
    }

    @Test void ringKeepsAConstantRadiusAndHeight() {
        List<Shapes.Offset> frame = Shapes.frame(Shape.RING, 3.0, 0.0, 12, 0.0, 0);
        for (Shapes.Offset offset : frame) {
            assertEquals(0.0, offset.y(), DELTA);
            assertEquals(3.0, Math.hypot(offset.x(), offset.z()), 1e-6);
        }
    }

    @Test void columnAndSpiralSpanTheConfiguredHeight() {
        for (Shape shape : List.of(Shape.COLUMN, Shape.SPIRAL)) {
            List<Shapes.Offset> frame = Shapes.frame(shape, 1.0, 4.0, 21, 0.0, 0);
            assertEquals(0.0, frame.getFirst().y(), DELTA);
            assertEquals(4.0, frame.getLast().y(), DELTA);
        }
    }

    @Test void sphereStaysInsideTheExpectedBounds() {
        double radius = 2.5;
        for (Shapes.Offset offset : Shapes.frame(Shape.SPHERE, radius, 0.0, 40, 0.0, 0)) {
            assertTrue(offset.y() >= 0 && offset.y() <= radius * 2 + DELTA, "sphere height");
            assertTrue(Math.hypot(offset.x(), offset.z()) <= radius + DELTA, "sphere width");
        }
    }

    @Test void rotationChangesTheFrameButNotTheHeight() {
        List<Shapes.Offset> first = Shapes.frame(Shape.SPIRAL, 2.0, 4.0, 17, 20.0, 0);
        List<Shapes.Offset> turned = Shapes.frame(Shape.SPIRAL, 2.0, 4.0, 17, 20.0, 1);
        assertEquals(first.size(), turned.size());
        assertEquals(first.getFirst().y(), turned.getFirst().y(), DELTA);
        assertTrue(Math.abs(first.getFirst().x() - turned.getFirst().x()) > 1e-6, "phase rotates the shape");
    }

    @Test void pointCountIsClamped() {
        assertEquals(1, Shapes.frame(Shape.RING, 1.0, 0.0, 0, 0.0, 0).size());
        assertEquals(Shapes.MAX_POINTS, Shapes.frame(Shape.RING, 1.0, 0.0, 10_000, 0.0, 0).size());
    }

    @Test void invalidNumbersFallBackInsteadOfProducingNaN() {
        List<Shapes.Offset> frame = Shapes.frame(Shape.BURST, Double.NaN, Double.NaN, 4, 0.0, 0);
        for (Shapes.Offset offset : frame) {
            assertTrue(Double.isFinite(offset.x()) && Double.isFinite(offset.y()) && Double.isFinite(offset.z()));
        }
    }

    @Test void shapeNamesAreCaseInsensitive() {
        assertEquals(Shape.RING, Shape.parse(" RING "));
        assertEquals(Shape.SPIRAL, Shape.parse("spiral"));
        assertNull(Shape.parse("circle"));
    }
}
