package dev.yanianz.atrium.module.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.module.staff.SnapshotLifecycle.JoinAction;
import dev.yanianz.atrium.module.staff.SnapshotLifecycle.State;
import org.junit.jupiter.api.Test;

class SnapshotLifecycleTest {
    @Test
    void onlyActiveRowsAreRestoredAutomatically() {
        assertEquals(JoinAction.NOTHING, SnapshotLifecycle.onJoin(null, false, false));
        assertEquals(JoinAction.RESTORE, SnapshotLifecycle.onJoin(State.ACTIVE, false, false));
        assertEquals(JoinAction.RESTORE, SnapshotLifecycle.onJoin(State.ACTIVE, false, true));
    }

    @Test
    void restoringRowIsNeverReappliedBlindly() {
        // Regression: a restore followed by a failed delete used to restore the same snapshot again on the
        // next join, duplicating everything stored in a chest meanwhile and overwriting newer items.
        assertEquals(JoinAction.HOLD_FOR_REVIEW, SnapshotLifecycle.onJoin(State.RESTORING, false, false));
    }

    @Test
    void restoringRowWithIdenticalInventoryIsMarkedRestored() {
        assertEquals(JoinAction.MARK_RESTORED, SnapshotLifecycle.onJoin(State.RESTORING, false, true));
    }

    @Test
    void corruptRowsAreHeld() {
        assertEquals(JoinAction.HOLD_FOR_REVIEW, SnapshotLifecycle.onJoin(State.ACTIVE, true, true));
        assertEquals(JoinAction.HOLD_FOR_REVIEW, SnapshotLifecycle.onJoin(State.RESTORING, true, true));
    }

    @Test
    void unknownStoredStatesAreHeld() {
        assertEquals(State.ACTIVE, State.parse("active"));
        assertEquals(State.RESTORING, State.parse("RESTORING"));
        assertEquals(State.RESTORING, State.parse("RESTORED?"));
        assertEquals(State.RESTORING, State.parse(null));
    }

    @Test
    void enteringNeedsAnEmptySlot() {
        assertTrue(SnapshotLifecycle.canEnter(null));
        assertFalse(SnapshotLifecycle.canEnter(State.ACTIVE));
        assertFalse(SnapshotLifecycle.canEnter(State.RESTORING), "a held row must never be overwritten");
    }

    @Test
    void recoverApplyRules() {
        assertTrue(SnapshotLifecycle.canApply(State.RESTORING, false, true, false));
        assertFalse(SnapshotLifecycle.canApply(State.ACTIVE, false, true, false));
        assertFalse(SnapshotLifecycle.canApply(State.RESTORING, true, true, false));
        assertFalse(SnapshotLifecycle.canApply(State.RESTORING, false, false, false));
        assertFalse(SnapshotLifecycle.canApply(State.RESTORING, false, true, true));
        assertFalse(SnapshotLifecycle.canApply(null, false, true, false));
    }

    @Test
    void recoverDiscardOnlyTouchesHeldRows() {
        assertTrue(SnapshotLifecycle.canDiscard(State.RESTORING));
        assertFalse(SnapshotLifecycle.canDiscard(State.ACTIVE));
        assertFalse(SnapshotLifecycle.canDiscard(null));
    }

    @Test
    void joinNeverMergesAnInventoryWithoutStaffTools() {
        // A crash before the next autosave brings back the pre-staff inventory; merging it would dupe it.
        assertEquals(ExtraItemsMode.CLEAR, SnapshotLifecycle.restoreMode(ExtraItemsMode.KEEP, true, false));
        assertEquals(ExtraItemsMode.CLEAR, SnapshotLifecycle.restoreMode(ExtraItemsMode.DROP, true, false));
        assertEquals(ExtraItemsMode.KEEP, SnapshotLifecycle.restoreMode(ExtraItemsMode.KEEP, true, true));
        assertEquals(ExtraItemsMode.KEEP, SnapshotLifecycle.restoreMode(ExtraItemsMode.KEEP, false, false));
        assertEquals(ExtraItemsMode.CLEAR, SnapshotLifecycle.restoreMode(null, false, true));
    }
}
