package dev.yanianz.atrium.module.staff;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Two-phase life cycle of a stored staff snapshot. Pure rules, unit-tested without a server.
 *
 * <pre>
 *   (no row) --enter--> ACTIVE --exit/join: written and confirmed first--> RESTORING --applied--> deleted
 * </pre>
 *
 * <p>Only {@link State#ACTIVE} rows are restored automatically. A {@link State#RESTORING} row means a
 * restore was started and may or may not have reached the inventory (crash, failed delete). It is never
 * applied blindly again: it is deleted when the inventory is byte-identical to the snapshot (the restore
 * already happened) and otherwise kept for {@code /staffmode recover}.
 */
final class SnapshotLifecycle {
    enum State {
        /** Saved; the player is (or was, before a crash) in staff mode. Restored automatically. */
        ACTIVE,
        /** A restore was confirmed in storage before the inventory was touched. Never re-applied blindly. */
        RESTORING;

        static State parse(@Nullable String value) {
            if (value == null) {
                return RESTORING; // unknown => safest choice: hold for review
            }
            try {
                return valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return RESTORING;
            }
        }
    }

    /** What to do with a row found when the player joins. */
    enum JoinAction {
        NOTHING,
        /** ACTIVE row: mark RESTORING, then apply it. */
        RESTORE,
        /** RESTORING row and the inventory already equals the snapshot: the restore happened; delete the row. */
        MARK_RESTORED,
        /** RESTORING row and the inventory differs: keep it for manual review, never apply automatically. */
        HOLD_FOR_REVIEW
    }

    private SnapshotLifecycle() {
    }

    /**
     * @param state           state of the stored row, {@code null} when there is none
     * @param corrupt         the row exists but its snapshot cannot be decoded
     * @param inventoryEquals the current inventory is byte-identical to the snapshot (only read for RESTORING)
     */
    static JoinAction onJoin(@Nullable State state, boolean corrupt, boolean inventoryEquals) {
        if (state == null) {
            return JoinAction.NOTHING;
        }
        if (corrupt) {
            return JoinAction.HOLD_FOR_REVIEW;
        }
        return switch (state) {
            case ACTIVE -> JoinAction.RESTORE;
            case RESTORING -> inventoryEquals ? JoinAction.MARK_RESTORED : JoinAction.HOLD_FOR_REVIEW;
        };
    }

    /** A new staff session may only start when no row exists (a RESTORING row waits for review). */
    static boolean canEnter(@Nullable State existing) {
        return existing == null;
    }

    /** {@code /staffmode recover apply}: only a held row, only for an online player not in staff mode. */
    static boolean canApply(@Nullable State state, boolean corrupt, boolean targetOnline, boolean targetInStaffMode) {
        return state == State.RESTORING && !corrupt && targetOnline && !targetInStaffMode;
    }

    /** {@code /staffmode recover discard}: only a held row, never the live snapshot of a staff session. */
    static boolean canDiscard(@Nullable State state) {
        return state == State.RESTORING;
    }

    /**
     * Mode used for items found in the inventory when a snapshot is restored. On join the inventory may come
     * from a save made <em>before</em> staff mode cleared it (crash before the next autosave); merging that
     * with the snapshot would duplicate every item. Only an inventory that still holds staff tools is proof
     * that it was saved during staff mode, so anything else is replaced, never merged.
     */
    static ExtraItemsMode restoreMode(ExtraItemsMode configured, boolean onJoin, boolean inventoryHasStaffTool) {
        if (onJoin && !inventoryHasStaffTool) {
            return ExtraItemsMode.CLEAR;
        }
        return configured == null ? ExtraItemsMode.CLEAR : configured;
    }
}
