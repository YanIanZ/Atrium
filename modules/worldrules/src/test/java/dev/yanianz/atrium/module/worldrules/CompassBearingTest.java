package dev.yanianz.atrium.module.worldrules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CompassBearingTest {
    @Test
    void straightAheadIsUp() {
        // Yaw 0 looks towards +z (south).
        assertEquals("\u2B06", CompassBearing.arrow(0, 10, 0));
        assertEquals(10, CompassBearing.distance(0, 10));
    }

    @Test
    void rightAndLeftFollowTheMinecraftYawDirection() {
        // Facing south, west (-x) is to the right.
        assertEquals("\u27A1", CompassBearing.arrow(-10, 0, 0));
        // East (+x) is to the left.
        assertEquals("\u2B05", CompassBearing.arrow(10, 0, 0));
        // Behind.
        assertEquals("\u2B07", CompassBearing.arrow(0, -10, 0));
    }

    @Test
    void diagonalArrows() {
        assertEquals("\u2197", CompassBearing.arrow(-10, 10, 0));
        assertEquals("\u2196", CompassBearing.arrow(10, 10, 0));
        assertEquals("\u2199", CompassBearing.arrow(10, -10, 0));
        assertEquals("\u2198", CompassBearing.arrow(-10, -10, 0));
    }

    @Test
    void rotationIsRelativeToTheHolder() {
        // Facing west (yaw 90), the target to the west is straight ahead.
        assertEquals("\u2B06", CompassBearing.arrow(-10, 0, 90));
        // Rotating by 360 degrees does not change the result.
        assertEquals(CompassBearing.arrow(-10, 10, 0), CompassBearing.arrow(-10, 10, 360));
    }

    @Test
    void relativeAngleStaysWithinHalfATurn() {
        assertEquals(0.0, CompassBearing.relative(0, 0), 0.0001);
        assertEquals(90.0, CompassBearing.relative(90, 0), 0.0001);
        assertEquals(-90.0, CompassBearing.relative(270, 0), 0.0001);
        assertEquals(180.0, CompassBearing.relative(180, 0), 0.0001);
        assertEquals(10.0, CompassBearing.relative(370, 0), 0.0001);
    }

    @Test
    void samePositionDoesNotCrash() {
        assertEquals("\u2B07", CompassBearing.arrow(0, 0, 45));
        assertEquals(0, CompassBearing.distance(0, 0));
        assertEquals("0m", CompassBearing.formatDistance(-5));
    }
}
