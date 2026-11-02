package dev.yanianz.atrium.module.inventories;

/**
 * Pure decision for an offline edit when its target joins. The edit is only applied while the live contents
 * still match the snapshot the administrator edited; anything else is discarded instead of overwriting items
 * the player gained in the meantime (the same rule as {@link BackupGuard}).
 */
final class PendingEditPolicy {
    enum Decision {
        APPLY,
        DISCARD_CHANGED,
        DISCARD_MISSING_BASE
    }

    private PendingEditPolicy() {}

    static Decision decide(boolean baseFound, boolean currentMatchesBase) {
        if (!baseFound) {
            return Decision.DISCARD_MISSING_BASE;
        }
        return currentMatchesBase ? Decision.APPLY : Decision.DISCARD_CHANGED;
    }

    /** Short reason code stored with a discarded row (and used in the staff message). */
    static String detail(Decision decision) {
        return switch (decision) {
            case APPLY -> "applied";
            case DISCARD_CHANGED -> "contents-changed";
            case DISCARD_MISSING_BASE -> "missing-base";
        };
    }
}
