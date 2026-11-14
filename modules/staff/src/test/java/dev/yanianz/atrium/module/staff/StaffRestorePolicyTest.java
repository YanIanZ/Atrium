package dev.yanianz.atrium.module.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.module.staff.StaffRestorePolicy.RestoreOutcome;
import org.junit.jupiter.api.Test;

class StaffRestorePolicyTest {
    @Test
    void onlyExactOriginIsAllowed() {
        assertTrue(StaffRestorePolicy.originAllowed("hub-1", "hub-1"));
        assertFalse(StaffRestorePolicy.originAllowed("hub-1", "island-2"));
        // Unknown/legacy origins on shared SQL are never silently adopted.
        assertFalse(StaffRestorePolicy.originAllowed("hub-1", ""));
        assertFalse(StaffRestorePolicy.originAllowed("hub-1", null));
        assertFalse(StaffRestorePolicy.originAllowed("hub-1", " hub-1"));
    }

    @Test
    void originDisplayNeverNull() {
        assertEquals("hub-1", StaffRestorePolicy.displayOrigin("hub-1"));
        assertEquals("?", StaffRestorePolicy.displayOrigin(""));
        assertEquals("?", StaffRestorePolicy.displayOrigin(null));
    }

    @Test
    void onlyUntouchedRestoresRevert() {
        assertTrue(StaffRestorePolicy.shouldRevertToActive(RestoreOutcome.NOT_APPLIED));
        assertFalse(StaffRestorePolicy.shouldRevertToActive(RestoreOutcome.PARTIAL));
        assertFalse(StaffRestorePolicy.shouldRevertToActive(RestoreOutcome.APPLIED));
    }

    @Test
    void onlyPartialRestoresHold() {
        assertTrue(StaffRestorePolicy.shouldHoldForReview(RestoreOutcome.PARTIAL));
        assertFalse(StaffRestorePolicy.shouldHoldForReview(RestoreOutcome.NOT_APPLIED));
        assertFalse(StaffRestorePolicy.shouldHoldForReview(RestoreOutcome.APPLIED));
    }

    @Test
    void joinMergesOnlyWithStaffToolsAtDestination() {
        assertEquals(ExtraItemsMode.CLEAR,
                StaffRestorePolicy.restoreMode(ExtraItemsMode.KEEP, true, false));
        assertEquals(ExtraItemsMode.KEEP,
                StaffRestorePolicy.restoreMode(ExtraItemsMode.KEEP, true, true));
        assertEquals(ExtraItemsMode.CLEAR,
                StaffRestorePolicy.restoreMode(ExtraItemsMode.CLEAR, true, true));
        assertEquals(ExtraItemsMode.KEEP,
                StaffRestorePolicy.restoreMode(ExtraItemsMode.KEEP, false, false));
    }
}
