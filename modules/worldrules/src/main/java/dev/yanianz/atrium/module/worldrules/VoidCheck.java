package dev.yanianz.atrium.module.worldrules;

/** Where the void rescue triggers, without server access. */
final class VoidCheck {
    private VoidCheck() {}

    /** Y level below which a player in a world with the given minimum height is rescued. */
    static double triggerY(int minHeight, int offset) {
        return minHeight - Math.max(0, Math.min(256, offset));
    }

    static boolean below(double y, int minHeight, int offset) {
        return Double.isFinite(y) && y < triggerY(minHeight, offset);
    }

    /** The last safe position must be in the same world and above the trigger level. */
    static boolean usableSafeSpot(double y, int minHeight, int offset) {
        return Double.isFinite(y) && y >= triggerY(minHeight, offset);
    }
}
