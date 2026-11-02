package dev.yanianz.atrium.module.inventories;

/**
 * Decisions behind {@code /clearall}: which kind of target was asked for, whether the sender may do it, whether a
 * second confirmation is required, and whether {@code -s} really applies.
 *
 * <p>Deliberately free of Bukkit so the rules can be unit-tested without a server. The command itself still
 * re-checks the live target (a player can disconnect between the check and the action).
 */
final class ClearAllPolicy {
    enum Kind { SELF, OTHER, ALL }

    enum Refusal {
        NONE,
        /** Clearing another player without {@code atrium.clearall.others}. */
        OTHERS_PERMISSION,
        /** Clearing every online player without {@code atrium.clearall.all}. */
        ALL_PERMISSION,
        /** The named player is not online; Paper cannot edit an offline inventory. */
        OFFLINE
    }

    private ClearAllPolicy() {}

    static Kind kind(boolean self, boolean everyone) {
        if (everyone) {
            return Kind.ALL;
        }
        return self ? Kind.SELF : Kind.OTHER;
    }

    /** Permission first, then availability: a sender without the permission is never told whether the target exists. */
    static Refusal check(Kind kind, boolean hasOthersPermission, boolean hasAllPermission, boolean targetOnline) {
        if (kind == Kind.ALL) {
            return hasAllPermission ? Refusal.NONE : Refusal.ALL_PERMISSION;
        }
        if (kind == Kind.OTHER && !hasOthersPermission) {
            return Refusal.OTHERS_PERMISSION;
        }
        return targetOnline ? Refusal.NONE : Refusal.OFFLINE;
    }

    /** Clearing yourself is a single command; other players and "all" must be confirmed by repeating the command. */
    static boolean needsConfirmation(Kind kind) {
        return kind != Kind.SELF;
    }

    /** {@code -s} is accepted by the command tree only with the permission, this is the second, defensive gate. */
    static boolean silent(boolean requested, boolean hasSilentPermission) {
        return requested && hasSilentPermission;
    }
}
