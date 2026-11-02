package dev.yanianz.atrium.module.inventories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Regression: a rejected edit returns what the editor added, an applied edit returns nothing. */
class RefundPlanTest {
    private static final UUID EDITOR = UUID.randomUUID();

    @Test
    void anAppliedEditNeverRefunds() {
        RefundPlan.Plan plan = RefundPlan.forDecision(PendingEditPolicy.Decision.APPLY, EDITOR, true, 64);
        assertEquals(RefundPlan.Delivery.NONE, plan.delivery());
        assertTrue(plan.nothing());
    }

    @Test
    void aRejectionRefundsAnOnlineEditorNow() {
        RefundPlan.Plan plan = RefundPlan.forDecision(PendingEditPolicy.Decision.DISCARD_CHANGED, EDITOR, true, 64);
        assertEquals(RefundPlan.Delivery.REFUND_NOW, plan.delivery());
        assertEquals(64, plan.items());
    }

    @Test
    void aRejectionQueuesForAnOfflineEditor() {
        RefundPlan.Plan plan = RefundPlan.forDecision(PendingEditPolicy.Decision.DISCARD_MISSING_BASE, EDITOR,
                false, 3);
        assertEquals(RefundPlan.Delivery.QUEUE_OFFLINE, plan.delivery());
        assertEquals(3, plan.items());
    }

    @Test
    void nothingIsReturnedWithoutAnEditorOrAddedItems() {
        assertEquals(RefundPlan.Delivery.NONE, RefundPlan.forRejection(null, true, 64).delivery());
        assertEquals(RefundPlan.Delivery.NONE, RefundPlan.forRejection(EDITOR, true, 0).delivery());
        assertEquals(RefundPlan.Delivery.NONE,
                RefundPlan.forDecision(PendingEditPolicy.Decision.DISCARD_CHANGED, EDITOR, false, 0).delivery());
    }

    @Test
    void aSupersededEditIsRefundedLikeARejection() {
        assertEquals(RefundPlan.Delivery.QUEUE_OFFLINE, RefundPlan.forRejection(EDITOR, false, 16).delivery());
        assertEquals(RefundPlan.Delivery.REFUND_NOW, RefundPlan.forRejection(EDITOR, true, 16).delivery());
    }

    @Test
    void portionsRespectTheMaximumStackSize() {
        assertEquals(List.of(64, 32), RefundPlan.portions(96, 64));
        assertEquals(List.of(1), RefundPlan.portions(1, 64));
        assertEquals(List.of(64), RefundPlan.portions(64, 64));
        assertEquals(List.of(), RefundPlan.portions(0, 64));
        assertEquals(List.of(), RefundPlan.portions(10, 0));
    }
}
