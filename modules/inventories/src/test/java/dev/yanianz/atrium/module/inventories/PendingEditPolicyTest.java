package dev.yanianz.atrium.module.inventories;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Regression: an offline edit must never overwrite contents that changed after the base snapshot. */
class PendingEditPolicyTest {
    @Test
    void identicalContentsAreApplied() {
        assertEquals(PendingEditPolicy.Decision.APPLY,
                PendingEditPolicy.decide(true, true));
    }

    @Test
    void changedContentsAreDiscarded() {
        assertEquals(PendingEditPolicy.Decision.DISCARD_CHANGED,
                PendingEditPolicy.decide(true, false));
    }

    @Test
    void aMissingBaseIsAlwaysDiscarded() {
        assertEquals(PendingEditPolicy.Decision.DISCARD_MISSING_BASE,
                PendingEditPolicy.decide(false, true));
        assertEquals(PendingEditPolicy.Decision.DISCARD_MISSING_BASE,
                PendingEditPolicy.decide(false, false));
    }

    @Test
    void detailsAreStableForTheHistoryAndStaffMessage() {
        assertEquals("applied", PendingEditPolicy.detail(PendingEditPolicy.Decision.APPLY));
        assertEquals("contents-changed", PendingEditPolicy.detail(PendingEditPolicy.Decision.DISCARD_CHANGED));
        assertEquals("missing-base", PendingEditPolicy.detail(PendingEditPolicy.Decision.DISCARD_MISSING_BASE));
    }
}
