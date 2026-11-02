package dev.yanianz.atrium.module.elytra;

/** Pure rules for the boost price and the Riptide guard. */
final class ElytraRules {
    private ElytraRules() {}

    /**
     * A Riptide trident already launches the player; boosting in the same tick multiplies that speed and
     * bypasses the normal cost/limits. The guard blocks it while the trident is held or the attack runs.
     */
    static boolean blocksBoost(boolean guard, boolean holdingRiptide, boolean riptiding) {
        return guard && (holdingRiptide || riptiding);
    }

    /**
     * Whether the player can pay a boost. Zero costs are always payable. The level is a minimum gate and
     * the points are the actual charge, so charging points can never be silently clamped at level zero.
     */
    static boolean canPay(int level, int totalExperiencePoints, int minimumLevels, int costPoints) {
        int requiredLevel = Math.max(0, minimumLevels);
        int requiredPoints = Math.max(0, costPoints);
        if (requiredLevel == 0 && requiredPoints == 0) {
            return true;
        }
        return level >= requiredLevel && totalExperiencePoints >= requiredPoints;
    }

    /** Configuration sanity. */
    static boolean validCost(int minimumLevels, int costPoints) {
        return minimumLevels >= 0 && minimumLevels <= 1000 && costPoints >= 0 && costPoints <= 100_000;
    }
}
