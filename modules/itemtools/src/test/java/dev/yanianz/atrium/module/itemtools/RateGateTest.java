package dev.yanianz.atrium.module.itemtools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RateGateTest {
    @Test
    void secondCallInsideTheWindowIsRefused() {
        RateGate gate = new RateGate();
        UUID player = UUID.randomUUID();
        assertTrue(gate.tryAcquire(player, "right", 1_000, 100));
        assertFalse(gate.tryAcquire(player, "right", 1_050, 100));
        assertTrue(gate.tryAcquire(player, "right", 1_100, 100));
        assertTrue(gate.tryAcquire(player, "right", 1_500, 100));
    }

    @Test
    void differentKeysAndPlayersAreIndependent() {
        RateGate gate = new RateGate();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertTrue(gate.tryAcquire(a, "right", 0, 500));
        assertTrue(gate.tryAcquire(a, "left", 0, 500));
        assertTrue(gate.tryAcquire(b, "right", 0, 500));
        assertFalse(gate.tryAcquire(a, "right", 10, 500));
        assertTrue(gate.tryAcquire(a, "right", 500, 500));
    }

    @Test
    void forgetReleasesTheWindowAndNonPositiveIntervalsAlwaysPass() {
        RateGate gate = new RateGate();
        UUID player = UUID.randomUUID();
        assertTrue(gate.tryAcquire(player, "consume", 2_000, 1_000));
        gate.forget(player);
        assertTrue(gate.tryAcquire(player, "consume", 2_001, 1_000));
        assertTrue(gate.tryAcquire(player, "consume", 2_002, 0));
        assertTrue(gate.tryAcquire(player, "consume", 2_002, -5));
        assertTrue(gate.tryAcquire(player, "consume", 1_000, 1_000), "a backwards clock never blocks");
        assertFalse(gate.tryAcquire(null, "x", 0, 5));
        assertFalse(gate.tryAcquire(player, null, 0, 5));
    }

    @Test
    void trackedKeysAreReportedPerPlayer() {
        RateGate gate = new RateGate();
        UUID player = UUID.randomUUID();
        assertTrue(gate.tryAcquire(player, "a", 0, 100));
        assertTrue(gate.tryAcquire(player, "b", 0, 100));
        assertTrue(gate.tracked(player) == 2);
        gate.forget(player);
        assertTrue(gate.tracked(player) == 0);
    }
}
