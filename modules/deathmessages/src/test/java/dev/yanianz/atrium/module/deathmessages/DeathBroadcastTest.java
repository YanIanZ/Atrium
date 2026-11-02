package dev.yanianz.atrium.module.deathmessages;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DeathBroadcastTest {
    @Test
    void zeroAndNegativeRadiusAreGlobal() {
        assertTrue(DeathBroadcast.receives(0, false, Double.MAX_VALUE));
        assertTrue(DeathBroadcast.receives(-1, false, 500));
        assertTrue(DeathBroadcast.receives(0, true, 0));
    }

    @Test
    void positiveRadiusRequiresTheSameWorld() {
        assertFalse(DeathBroadcast.receives(10, false, 1));
        assertTrue(DeathBroadcast.receives(10, true, 100));
    }

    @Test
    void radiusBoundaryIsInclusive() {
        assertTrue(DeathBroadcast.receives(10, true, 100.0));
        assertFalse(DeathBroadcast.receives(10, true, 100.0001));
        assertTrue(DeathBroadcast.receives(1, true, 1.0));
        assertFalse(DeathBroadcast.receives(1, true, 1.01));
    }

    @Test
    void tooFewPlayersHideTheBroadcast() {
        assertFalse(DeathBroadcast.receives(0, true, 0, 2, 3));
        assertTrue(DeathBroadcast.receives(0, true, 0, 3, 3));
        assertTrue(DeathBroadcast.receives(10, true, 4, 99, 3));
        assertFalse(DeathBroadcast.receives(10, false, 4, 99, 3), "the radius rule still applies");
        assertTrue(DeathBroadcast.receives(0, false, 0, 1, 0), "zero disables the auto-hide");
    }
}
