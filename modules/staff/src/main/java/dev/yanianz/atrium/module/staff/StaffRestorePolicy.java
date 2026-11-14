package dev.yanianz.atrium.module.staff;

import org.jspecify.annotations.Nullable;

/**
 * Pure restore decisions for staff snapshots, testable without a server.
 *
 * <p>A restore ends in exactly one of three outcomes: the inventory was provably untouched
 * ({@link RestoreOutcome#NOT_APPLIED}), it may have been changed ({@link RestoreOutcome#PARTIAL}),
 * or it was fully applied ({@link RestoreOutcome#APPLIED}). Only {@code NOT_APPLIED} may go back
 * to {@code ACTIVE} with a single exact-token compare-and-set; anything else stays {@code RESTORING}
 * for {@code /staffmode recover}. A failed teleport, a retired entity task, a token mismatch and a
 * missing world are all {@code NOT_APPLIED}: none of them touches the inventory.
 *
 * <p>Backend ownership is exact. A {@code null} or blank origin (rows written before ownership
 * tracking on a shared SQL backend) is never silently adopted: the row is preserved and the
 * operator is told to review it. Explicit adoption is a deliberate operator action elsewhere.
 */
final class StaffRestorePolicy {
    /** How far a restore got. Decides between a safe revert and holding the row for review. */
    enum RestoreOutcome {
        /** The inventory was provably not touched: a single CAS back to ACTIVE is safe. */
        NOT_APPLIED,
        /** The inventory may have been changed: keep the row as RESTORING and keep the lock. */
        PARTIAL,
        /** The snapshot was fully applied: the row can be archived and deleted. */
        APPLIED
    }

    private StaffRestorePolicy() {
    }

    /**
     * Whether the row belongs to this backend. Unknown or legacy origins are refused, never adopted.
     *
     * @param serverId the backend identity frozen when the manager was created (never {@code null})
     * @param origin   the row's stored origin, possibly {@code null} or blank for legacy rows
     */
    static boolean originAllowed(String serverId, @Nullable String origin) {
        return serverId != null && !serverId.isEmpty() && serverId.equals(origin);
    }

    /** Never-{@code null} origin text for operator-facing messages. */
    static String displayOrigin(@Nullable String origin) {
        return origin == null || origin.isEmpty() ? "?" : origin;
    }

    /** Only a restore that provably did not touch the inventory may revert to ACTIVE. */
    static boolean shouldRevertToActive(RestoreOutcome outcome) {
        return outcome == RestoreOutcome.NOT_APPLIED;
    }

    /** A restore that may have changed the inventory keeps the row and the inventory lock. */
    static boolean shouldHoldForReview(RestoreOutcome outcome) {
        return outcome == RestoreOutcome.PARTIAL;
    }

    /**
     * Merge rule, evaluated at the destination after the teleport, never before it: on a join the
     * inventory may come from an older save made before staff mode cleared it (crash before the next
     * autosave), so it is only merged when it still holds staff tools. Anything else is replaced,
     * never merged with an unrelated destination profile inventory.
     */
    static ExtraItemsMode restoreMode(ExtraItemsMode configured, boolean onJoin, boolean inventoryHasStaffTool) {
        return SnapshotLifecycle.restoreMode(configured, onJoin, inventoryHasStaffTool);
    }
}
