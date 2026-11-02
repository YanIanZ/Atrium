package dev.yanianz.atrium.module.particles.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShapesTest {
    private static final WingPattern WINGS = WingPattern.defaults();
    private static final double RADIUS = 1.0;

    private static ShapeFrame frame(int tick, float yaw, boolean moving, double density, double speed) {
        return new ShapeFrame(tick, yaw, moving, RADIUS, density, speed, WINGS);
    }

    @Test
    void everyStyleProducesFinitePointsWhileMoving() {
        for (Style style : Style.values()) {
            double[] offsets = Shapes.offsets(style, frame(40, 37.0f, true, 1.0, 6.0));
            assertTrue(offsets.length % 3 == 0, style + " returned a partial point");
            assertTrue(offsets.length > 0, style + " returned no points while moving");
            for (int i = 0; i < offsets.length; i++) {
                assertTrue(Double.isFinite(offsets[i]), style + " returned a non-finite offset");
            }
        }
    }

    @Test
    void horizontalBoundsStayNearTheConfiguredRadius() {
        for (Style style : Style.values()) {
            double[] offsets = Shapes.offsets(style, frame(25, 0.0f, true, 1.0, 6.0));
            double limit = switch (style) {
                case TRAIL, ARROWS_TRAIL -> RADIUS + 2.5;
                default -> RADIUS + 0.5;
            };
            for (int i = 0; i < offsets.length; i += 3) {
                double horizontal = Math.hypot(offsets[i], offsets[i + 2]);
                assertTrue(horizontal <= limit,
                        style + " point " + (i / 3) + " is " + horizontal + " blocks out (limit " + limit + ")");
            }
        }
    }

    @Test
    void verticalBoundsAreReasonable() {
        for (Style style : Style.values()) {
            double[] offsets = Shapes.offsets(style, frame(25, 0.0f, true, 1.0, 6.0));
            for (int i = 1; i < offsets.length; i += 3) {
                assertTrue(offsets[i] >= -0.5 && offsets[i] <= 4.5,
                        style + " y=" + offsets[i] + " is outside the expected range");
            }
        }
    }

    @Test
    void movingOnlyShapesAreEmptyWhileStanding() {
        for (Style style : Style.values()) {
            double[] offsets = Shapes.offsets(style, frame(10, 0.0f, false, 1.0, 6.0));
            if (style.movingOnly()) {
                assertEquals(0, offsets.length, style + " must not draw while standing");
            } else {
                assertTrue(offsets.length > 0, style + " should draw while standing");
            }
        }
    }

    @Test
    void everyShapeRotatesWithThePlayerYaw() {
        for (Style style : Style.values()) {
            double[] north = Shapes.offsets(style, frame(10, 0.0f, true, 1.0, 6.0));
            double[] west = Shapes.offsets(style, frame(10, 90.0f, true, 1.0, 6.0));
            assertEquals(north.length, west.length, style + " changed its point count with yaw");
            boolean differs = false;
            for (int i = 0; i < north.length; i++) {
                if (Math.abs(north[i] - west[i]) > 1.0e-6) {
                    differs = true;
                    break;
                }
            }
            assertTrue(differs, style + " did not rotate with yaw");
        }
    }

    @Test
    void helixSpansTwoBlocks() {
        double[] offsets = Shapes.offsets(Style.HELIX, frame(0, 0.0f, true, 1.0, 6.0));
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (int i = 1; i < offsets.length; i += 3) {
            min = Math.min(min, offsets[i]);
            max = Math.max(max, offsets[i]);
        }
        assertTrue(min >= 0.0 && min <= 0.05, "helix should start at the feet");
        assertTrue(max >= 1.95 && max <= 2.05, "helix should end near the head");
    }

    @Test
    void pulseRingStartsSmallAndGrows() {
        double[] start = Shapes.offsets(Style.PULSE_RING, frame(0, 0.0f, true, 1.0, 4.0));
        double[] half = Shapes.offsets(Style.PULSE_RING, frame(45, 0.0f, true, 1.0, 4.0));
        assertTrue(maxHorizontal(start) < 0.01, "pulse ring must start at the centre");
        assertTrue(maxHorizontal(half) > 0.3, "pulse ring must grow");
        assertTrue(maxHorizontal(half) <= RADIUS + 0.01, "pulse ring must not exceed the radius");
    }

    @Test
    void densityThinsTheWingBitmap() {
        double[] full = Shapes.offsets(Style.WINGS, frame(0, 0.0f, true, 1.0, 0.0));
        double[] half = Shapes.offsets(Style.WINGS, frame(0, 0.0f, true, 0.5, 0.0));
        assertTrue(Shapes.pointCount(half) < Shapes.pointCount(full), "lower density should use fewer points");
        assertTrue(Shapes.pointCount(half) > 0);
    }

    @Test
    void radiusScalesTheHalo() {
        double small = maxHorizontal(Shapes.offsets(Style.HALO,
                new ShapeFrame(0, 0.0f, true, 0.5, 1.0, 4.0, WINGS)));
        double large = maxHorizontal(Shapes.offsets(Style.HALO,
                new ShapeFrame(0, 0.0f, true, 1.5, 1.0, 4.0, WINGS)));
        assertTrue(small < large, "a larger radius should produce a wider halo");
    }

    private static double maxHorizontal(double[] offsets) {
        double max = 0.0;
        for (int i = 0; i < offsets.length; i += 3) {
            max = Math.max(max, Math.hypot(offsets[i], offsets[i + 2]));
        }
        return max;
    }
}
