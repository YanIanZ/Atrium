package dev.yanianz.atrium.module.portals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PortalKickbackTest {
    @Test
    void reversesAndDampsTheMovement() {
        PortalKickback.Vec velocity = PortalKickback.velocity(1.0, 0.0, -2.0, 0.9);
        assertEquals(-0.9, velocity.x(), 1e-9);
        assertEquals(1.8, velocity.z(), 1e-9);
        assertTrue(velocity.y() >= 0.2, "a kickback always lifts the player slightly");
    }

    @Test
    void clampsToASafeKick() {
        PortalKickback.Vec velocity = PortalKickback.velocity(-100, -100, 100, 4);
        assertEquals(3.0, velocity.x(), 1e-9);
        assertEquals(3.0, velocity.y(), 1e-9);
        assertEquals(-3.0, velocity.z(), 1e-9);
    }

    @Test
    void strengthIsClamped() {
        assertEquals(PortalKickback.velocity(-1, 0, 0, 0.01).x(), 0.1, 1e-9);
        assertEquals(PortalKickback.velocity(-1, 0, 0, 100).x(), 3.0, 1e-9);
    }

    @Test
    void standingStillStillPushesUp() {
        PortalKickback.Vec velocity = PortalKickback.velocity(0, 0, 0, 0.9);
        assertEquals(0, velocity.x(), 1e-9);
        assertEquals(0.2, velocity.y(), 1e-9);
        assertEquals(0, velocity.z(), 1e-9);
    }
}
