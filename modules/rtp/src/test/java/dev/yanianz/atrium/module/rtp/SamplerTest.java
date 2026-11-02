package dev.yanianz.atrium.module.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.module.rtp.Sampler.Area;
import dev.yanianz.atrium.module.rtp.Sampler.Border;
import dev.yanianz.atrium.module.rtp.Sampler.Point;
import dev.yanianz.atrium.module.rtp.Sampler.Shape;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SamplerTest {
    private static final int N = 40_000;

    private static double radius(Area area, Point p) {
        return Math.hypot(p.x() + 0.5 - area.centerX(), p.z() + 0.5 - area.centerZ());
    }

    @Test
    void circleStaysInRingAndIsUniformByArea() {
        Area area = new Area(100, -50, 200, 1000, Shape.CIRCLE, null);
        Random random = new Random(1);
        // Half of the ring's area lies inside this radius.
        double halfAreaRadius = Math.sqrt((200.0 * 200 + 1000.0 * 1000) / 2);
        int inner = 0;
        for (int i = 0; i < N; i++) {
            Point p = Sampler.sample(area, random);
            double r = radius(area, p);
            assertTrue(r >= 200 - 1 && r <= 1000 + 1, "radius " + r);
            if (r < halfAreaRadius) {
                inner++;
            }
        }
        assertEquals(0.5, inner / (double) N, 0.02);
    }

    @Test
    void fullDiscIsUniformToo() {
        Area area = new Area(0, 0, 0, 500, Shape.CIRCLE, null);
        Random random = new Random(2);
        int inner = 0;
        for (int i = 0; i < N; i++) {
            if (radius(area, Sampler.sample(area, random)) < 500 / Math.sqrt(2)) {
                inner++;
            }
        }
        assertEquals(0.5, inner / (double) N, 0.02);
    }

    @Test
    void squareRingCoversSidesNotOnlyCorners() {
        Area area = new Area(0, 0, 200, 1000, Shape.SQUARE, null);
        Random random = new Random(3);
        int sideStrips = 0;
        int[] quadrants = new int[4];
        for (int i = 0; i < N; i++) {
            Point p = Sampler.sample(area, random);
            double dx = p.x() + 0.5;
            double dz = p.z() + 0.5;
            double ring = Math.max(Math.abs(dx), Math.abs(dz));
            assertTrue(ring >= 200 - 1 && ring <= 1000 + 1, "ring " + ring);
            if (Math.abs(dz) < 200) {
                sideStrips++;
            }
            quadrants[(dx >= 0 ? 0 : 1) + (dz >= 0 ? 0 : 2)]++;
        }
        // Left + right strips hold min / (max + min) of the ring's area.
        assertEquals(200.0 / 1200.0, sideStrips / (double) N, 0.02);
        for (int q : quadrants) {
            assertEquals(0.25, q / (double) N, 0.02);
        }
    }

    @Test
    void borderClampUsesHalfTheSize() {
        // Border of size 1000 around 0,0: the inside is -500..500, not -1000..1000.
        Border border = Border.of(0, 0, 1000);
        for (Shape shape : Shape.values()) {
            Area area = new Area(0, 0, 200, 3000, shape, border);
            Random random = new Random(4);
            for (int i = 0; i < N; i++) {
                Point p = Sampler.sample(area, random);
                assertTrue(border.containsBlock(p.x(), p.z()), shape + " outside border: " + p);
                assertTrue(p.x() >= -500 + Sampler.BORDER_MARGIN && p.x() + 1 <= 500 - Sampler.BORDER_MARGIN);
                assertTrue(p.z() >= -500 + Sampler.BORDER_MARGIN && p.z() + 1 <= 500 - Sampler.BORDER_MARGIN);
            }
        }
    }

    @Test
    void ringFullyOutsideTheBorderFallsBackInside() {
        Border border = Border.of(0, 0, 1000);
        // Ring centred far outside the border.
        Area away = new Area(5000, 5000, 0, 100, Shape.CIRCLE, border);
        // Ring larger than the border with min radius beyond it: no ring point is inside.
        Area hollow = new Area(0, 0, 900, 1200, Shape.SQUARE, border);
        Random random = new Random(5);
        for (int i = 0; i < 5_000; i++) {
            assertTrue(border.containsBlock(Sampler.sample(away, random).x(), Sampler.sample(away, random).z()));
            Point p = Sampler.sample(hollow, random);
            assertTrue(border.containsBlock(p.x(), p.z()), "hollow " + p);
        }
    }

    @Test
    void offCentreBorderAndPartialOverlap() {
        Border border = Border.of(-3000, 250, 600); // inside: x -3300..-2700, z -50..550
        Area area = new Area(-2800, 0, 0, 400, Shape.CIRCLE, border);
        Random random = new Random(6);
        for (int i = 0; i < N; i++) {
            Point p = Sampler.sample(area, random);
            assertTrue(border.containsBlock(p.x(), p.z()), "outside " + p);
            assertTrue(radius(area, p) <= 400 + 1);
        }
    }

    @Test
    void radiiAreNormalised() {
        Area area = new Area(0, 0, 50, 10, Shape.CIRCLE, null);
        assertEquals(50, area.min());
        assertEquals(51, area.max());
        Area negative = new Area(0, 0, -5, 0, Shape.SQUARE, null);
        assertEquals(0, negative.min());
        assertEquals(1, negative.max());
        Random random = new Random(7);
        for (int i = 0; i < 1000; i++) {
            double r = radius(area, Sampler.sample(area, random));
            assertTrue(r >= 49 && r <= 52, "radius " + r);
        }
    }
}
