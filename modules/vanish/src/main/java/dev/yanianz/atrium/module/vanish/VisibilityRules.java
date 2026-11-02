package dev.yanianz.atrium.module.vanish;

/** Pure visibility arithmetic, kept free of Bukkit types so it can be unit tested. */
final class VisibilityRules {
    private VisibilityRules() {}

    /**
     * Level up to which a viewer sees vanished players: 0 without {@code atrium.vanish.see}, otherwise the
     * numeric {@code atrium.vanish.see.<n>} level (at least 1).
     */
    static int seeLevel(boolean hasSeePermission, int numericLevel) {
        return hasSeePermission ? Math.max(1, numericLevel) : 0;
    }

    /** Level of a vanished player; never below 1, so a viewer without the see permission never sees them. */
    static int vanishLevel(int numericLevel) {
        return Math.max(1, numericLevel);
    }

    /** Whether a viewer sees a target. A player always sees themselves and every player who is not vanished. */
    static boolean canSee(boolean samePlayer, boolean targetVanished, int viewerSeeLevel, int targetLevel) {
        if (samePlayer || !targetVanished) {
            return true;
        }
        return viewerSeeLevel > 0 && viewerSeeLevel >= vanishLevel(targetLevel);
    }

    /** Player count reported to the server list after removing online vanished players. */
    static int visibleCount(int reported, int hiddenOnline) {
        return Math.max(0, reported - Math.max(0, hiddenOnline));
    }
}
