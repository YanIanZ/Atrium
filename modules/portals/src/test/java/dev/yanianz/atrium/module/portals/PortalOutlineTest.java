package dev.yanianz.atrium.module.portals;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PortalOutlineTest {
    @Test void pointsStayOnTheBoxEdgesAndWithinBudget() {
        Cuboid box = Cuboid.of("w", 0, 0, 0, 1, 2, 0); // space 2 x 3 x 1
        double[] points = PortalOutline.edgePoints(box, 0.5, 1000);
        assertEquals(0, points.length % 3);
        // total edge length 4*(2+3+1) = 24 at spacing 0.5 -> 48 points
        assertEquals(48 * 3, points.length);
        for (int i = 0; i < points.length; i += 3) {
            int onFaces = 0;
            onFaces += points[i] == 0 || points[i] == 2 ? 1 : 0;
            onFaces += points[i + 1] == 0 || points[i + 1] == 3 ? 1 : 0;
            onFaces += points[i + 2] == 0 || points[i + 2] == 1 ? 1 : 0;
            assertTrue(onFaces >= 2, "point not on an edge");
        }
        Cuboid big = Cuboid.of("w", 0, 0, 0, 127, 127, 127);
        assertTrue(PortalOutline.edgePoints(big, 0.25, 600).length / 3 <= 600);
    }
}
