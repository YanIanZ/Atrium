package dev.yanianz.atrium.module.afk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.afk.AfkReason;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AfkTrackerTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_WORLD = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final AfkRules NO_POOL =
            new AfkRules(0.1, false, 2.0, true, 4.0, false, 3.0, 0);
    private static final AfkRules POOL =
            new AfkRules(0.1, false, 2.0, true, 4.0, true, 3.0, 60_000);

    private static AfkPosition at(double x, double y, double z) {
        return new AfkPosition(WORLD, x, y, z, 0, 0);
    }

    private static AfkPosition look(double yaw, double pitch) {
        return new AfkPosition(WORLD, 0, 0, 0, yaw, pitch);
    }

    @Test
    void subThresholdMovesAccumulateInsteadOfBeingLost() {
        AfkTracker tracker = new AfkTracker(UUID.randomUUID(), 0, at(0, 0, 0));
        assertEquals(AfkTracker.ActivityResult.IGNORED,
                tracker.recordMovement(at(0.06, 0, 0), MovementFlags.NONE, 1000, NO_POOL));
        assertEquals(AfkTracker.ActivityResult.ACTIVE,
                tracker.recordMovement(at(0.12, 0, 0), MovementFlags.NONE, 2000, NO_POOL));
        assertEquals(2000, tracker.lastActivityAt());
    }

    @Test
    void headRotationOnlyIsIgnoredByDefaultAndCountedWhenEnabled() {
        AfkTracker ignored = new AfkTracker(UUID.randomUUID(), 0, look(0, 0));
        assertEquals(AfkTracker.ActivityResult.IGNORED,
                ignored.recordMovement(look(90, 0), MovementFlags.NONE, 1000, NO_POOL));
        assertEquals(0, ignored.lastActivityAt());

        AfkRules rotation = new AfkRules(0.1, true, 2.0, true, 4.0, false, 3.0, 0);
        AfkTracker counted = new AfkTracker(UUID.randomUUID(), 0, look(0, 0));
        assertEquals(AfkTracker.ActivityResult.ACTIVE,
                counted.recordMovement(look(90, 0), MovementFlags.NONE, 1000, rotation));
    }

    @Test
    void waterAndVehicleMovementDoNotCountAsActivity() {
        AfkTracker tracker = new AfkTracker(UUID.randomUUID(), 0, at(0, 0, 0));
        assertEquals(AfkTracker.ActivityResult.IGNORED,
                tracker.recordMovement(at(1, 0, 0), new MovementFlags(false, true, false, false), 1000, NO_POOL));
        assertEquals(AfkTracker.ActivityResult.IGNORED,
                tracker.recordMovement(at(2, 0, 0), new MovementFlags(true, false, false, false), 2000, NO_POOL));
        assertEquals(0, tracker.lastActivityAt());
    }

    @Test
    void activeSwimmingAndBoatingStillCount() {
        AfkTracker tracker = new AfkTracker(UUID.randomUUID(), 0, at(0, 0, 0));
        assertEquals(AfkTracker.ActivityResult.ACTIVE,
                tracker.recordMovement(at(1, 0, 0), new MovementFlags(false, true, false, true), 1000, NO_POOL));
        assertEquals(AfkTracker.ActivityResult.ACTIVE,
                tracker.recordMovement(at(2, 0, 0), new MovementFlags(true, false, false, true), 2000, NO_POOL));
    }

    @Test
    void suddenPistonLikePushIsIgnored() {
        AfkTracker tracker = new AfkTracker(UUID.randomUUID(), 0, at(0, 0, 0));
        assertEquals(AfkTracker.ActivityResult.IGNORED,
                tracker.recordMovement(at(10, 0, 0), MovementFlags.NONE, 1000, NO_POOL));
        assertEquals(0, tracker.lastActivityAt());
    }

    @Test
    void repeatedTinyMovementInsideThePoolBoxStopsCountingAfterTheWindow() {
        AfkTracker tracker = new AfkTracker(UUID.randomUUID(), 1_000, at(0, 0, 0));
        assertEquals(AfkTracker.ActivityResult.ACTIVE,
                tracker.recordMovement(at(0.5, 0, 0), MovementFlags.NONE, 2_000, POOL));
        assertEquals(AfkTracker.ActivityResult.POOLED,
                tracker.recordMovement(at(1.0, 0, 0), MovementFlags.NONE, 70_000, POOL));
        assertEquals(AfkTracker.ActivityResult.POOLED,
                tracker.recordMovement(at(1.5, 0, 0), MovementFlags.NONE, 130_000, POOL));
    }

    @Test
    void leavingTheBoxStillCountsAfterTheWindow() {
        AfkTracker tracker = new AfkTracker(UUID.randomUUID(), 1_000, at(0, 0, 0));
        assertEquals(AfkTracker.ActivityResult.POOLED,
                tracker.recordMovement(at(1.0, 0, 0), MovementFlags.NONE, 70_000, POOL));
        assertEquals(AfkTracker.ActivityResult.ACTIVE,
                tracker.recordMovement(at(3.5, 0, 0), MovementFlags.NONE, 71_000, POOL));
        assertEquals(71_000, tracker.lastActivityAt());
    }

    @Test
    void leavingTheBoxCountsImmediately() {
        AfkTracker tracker = new AfkTracker(UUID.randomUUID(), 1_000, at(0, 0, 0));
        assertEquals(AfkTracker.ActivityResult.ACTIVE,
                tracker.recordMovement(at(3.5, 0, 0), MovementFlags.NONE, 2_000, POOL));
    }

    @Test
    void chatStartsAFreshPoolAndCountsMovementImmediately() {
        AfkTracker tracker = new AfkTracker(UUID.randomUUID(), 1_000, at(0, 0, 0));
        tracker.recordActivity(2_000);
        assertEquals(AfkTracker.ActivityResult.ACTIVE,
                tracker.recordMovement(at(0.5, 0, 0), MovementFlags.NONE, 3_000, POOL));
    }

    @Test
    void changingWorldCountsAsActivity() {
        AfkTracker tracker = new AfkTracker(UUID.randomUUID(), 0, at(0, 0, 0));
        AfkPosition other = new AfkPosition(OTHER_WORLD, 0, 0, 0, 0, 0);
        assertEquals(AfkTracker.ActivityResult.ACTIVE,
                tracker.recordMovement(other, MovementFlags.NONE, 1000, NO_POOL));
    }

    @Test
    void sessionRestoresThePreviousFlags() {
        AfkTracker tracker = new AfkTracker(UUID.randomUUID(), 0, at(0, 0, 0));
        assertTrue(tracker.beginAfk(5_000, AfkReason.MANUAL, "brb", true, false, true, true));
        assertTrue(tracker.isAfk());
        assertEquals(5_000, tracker.afkSince());
        assertEquals("brb", tracker.message());
        assertEquals(AfkReason.MANUAL, tracker.reason());

        AfkTracker.Exit exit = tracker.endAfk(65_000);
        assertFalse(tracker.isAfk());
        assertEquals(60_000, exit.durationMillis());
        assertTrue(exit.restoreSleepingIgnored());
        assertFalse(exit.previousSleepingIgnored());
        assertTrue(exit.restoreInvulnerable());
        assertTrue(exit.previousInvulnerable());
        assertEquals(65_000, tracker.lastActivityAt());
    }

    @Test
    void actionsRunOnlyOncePerSession() {
        AfkTracker tracker = new AfkTracker(UUID.randomUUID(), 0, at(0, 0, 0));
        tracker.beginAfk(1_000, AfkReason.AUTOMATIC, "", false, false, false, false);
        assertTrue(tracker.claimActions());
        assertFalse(tracker.claimActions());
        tracker.endAfk(2_000);
        tracker.beginAfk(3_000, AfkReason.MANUAL, "", false, false, false, false);
        assertTrue(tracker.claimActions());
    }

    @Test
    void cancelledAutomaticChangeIsPostponed() {
        AfkTracker tracker = new AfkTracker(UUID.randomUUID(), 0, at(0, 0, 0));
        assertTrue(tracker.canAutoChange(1_000));
        tracker.postpone(31_000);
        assertFalse(tracker.canAutoChange(30_999));
        assertTrue(tracker.canAutoChange(31_000));
        tracker.recordActivity(40_000);
        assertTrue(tracker.canAutoChange(31_000));
    }
}
