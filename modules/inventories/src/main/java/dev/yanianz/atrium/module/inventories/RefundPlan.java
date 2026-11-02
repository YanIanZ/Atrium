package dev.yanianz.atrium.module.inventories;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Pure planning of the return path for an offline edit that will never reach its target.
 *
 * <p>Only stacks the administrator added are returned. A rejection or a superseded edit returns them on the
 * administrator's own thread (or queues them until their next join); an applied edit returns nothing, because
 * the items went to the target as intended. Removed target items are never returned: offline removal is a
 * deliberate deletion.
 */
final class RefundPlan {
    enum Delivery {
        /** Nothing to return (no editor, no added items, or an edit that was applied). */
        NONE,
        /** The editor is online: give the items on their own thread. */
        REFUND_NOW,
        /** The editor is offline: keep the items until their next join. */
        QUEUE_OFFLINE
    }

    record Plan(Delivery delivery, int items) {
        static final Plan NOTHING = new Plan(Delivery.NONE, 0);

        boolean nothing() {
            return delivery == Delivery.NONE;
        }
    }

    private RefundPlan() {}

    /** An edit that is applied must never refund anything. */
    static Plan forDecision(PendingEditPolicy.Decision decision, @Nullable UUID editor, boolean editorOnline,
            int addedItems) {
        if (decision == PendingEditPolicy.Decision.APPLY) {
            return Plan.NOTHING;
        }
        return forRejection(editor, editorOnline, addedItems);
    }

    /** A discarded or superseded edit: refund to the editor, online now or on their next join. */
    static Plan forRejection(@Nullable UUID editor, boolean editorOnline, int addedItems) {
        if (editor == null || addedItems <= 0) {
            return Plan.NOTHING;
        }
        return new Plan(editorOnline ? Delivery.REFUND_NOW : Delivery.QUEUE_OFFLINE, addedItems);
    }

    /**
     * Splits one added amount into stacks that respect the item's maximum stack size, so a refund always
     * rebuilds ordinary stacks instead of an impossible oversized one.
     */
    static List<Integer> portions(int items, int maxStack) {
        if (items <= 0 || maxStack <= 0) {
            return List.of();
        }
        List<Integer> portions = new ArrayList<>();
        int remaining = items;
        while (remaining > 0) {
            int portion = Math.min(remaining, maxStack);
            portions.add(portion);
            remaining -= portion;
        }
        return List.copyOf(portions);
    }
}
