package dev.yanianz.atrium.module.npcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

class NpcGeometryTest {
    private static Location at(double x, double y, double z) {
        return new Location(null, x, y, z, 0, 0);
    }

    @Test void yawFollowsBukkitConventions() {
        assertEquals(0f, NpcGeometry.lookAt(at(0, 0, 0), at(0, 0, 10))[0], 0.001);
        assertEquals(-90f, NpcGeometry.lookAt(at(0, 0, 0), at(10, 0, 0))[0], 0.001);
        assertEquals(90f, NpcGeometry.lookAt(at(0, 0, 0), at(-10, 0, 0))[0], 0.001);
        assertEquals(180f, Math.abs(NpcGeometry.lookAt(at(0, 0, 0), at(0, 0, -10))[0]), 0.001);
    }

    @Test void pitchUsesNegativeUpAndPositiveDown() {
        assertEquals(-90f, NpcGeometry.lookAt(at(0, 0, 0), at(0, 10, 0))[1], 0.001);
        assertEquals(90f, NpcGeometry.lookAt(at(0, 0, 0), at(0, -10, 0))[1], 0.001);
        assertEquals(-45f, NpcGeometry.lookAt(at(0, 0, 0), at(0, 10, 10))[1], 0.001);
    }

    @Test void alignmentIsOneWhenTheTargetIsStraightAhead() {
        Location eye = at(0, 0, 0);
        assertEquals(1.0, NpcGeometry.alignment(eye, new Vector(0, 0, 1), at(0, 0, 10)), 0.001);
        assertEquals(-1.0, NpcGeometry.alignment(eye, new Vector(0, 0, 1), at(0, 0, -10)), 0.001);
        assertEquals(0.0, NpcGeometry.alignment(eye, new Vector(0, 0, 1), at(10, 0, 0)), 0.001);
    }

    @Test void selectPrefersTheClosestCandidateInsideTheCone() {
        Location eye = at(0, 0, 0);
        Vector ahead = new Vector(0, 0, 1);
        List<Location> candidates = Arrays.asList(at(0, 0, -3), at(0, 0, 8), at(0, 0, 3), at(20, 0, 0));
        assertEquals(2, NpcGeometry.select(eye, ahead, candidates, 10, 0.5));
    }

    @Test void selectReturnsMinusOneWhenNothingIsInFront() {
        Location eye = at(0, 0, 0);
        assertTrue(NpcGeometry.select(eye, new Vector(0, 0, 1), List.of(at(0, 0, -5)), 10, 0.5) < 0);
        assertTrue(NpcGeometry.select(eye, new Vector(0, 0, 1), List.of(at(0, 0, 50)), 10, 0.5) < 0);
    }
}
