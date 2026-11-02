package dev.yanianz.atrium.module.mobs;

/**
 * Chance of a head drop. Pure: the looting bonus is applied to the base chance and clamped to 100, so a
 * lucky roll never has to ask for more than one head.
 */
final class HeadDropRules {
    private HeadDropRules() {}

    /**
     * @param basePercent     configured chance for this entity type, in percent
     * @param lootingLevel    looting level of the killer's tool (0 when unenchanted)
     * @param bonusPerLevel   fraction of the base chance added per looting level (0.02 = +2 %)
     */
    static double chance(double basePercent, int lootingLevel, double bonusPerLevel) {
        if (!(basePercent > 0) || !Double.isFinite(basePercent)) {
            return 0;
        }
        double bonus = Double.isFinite(bonusPerLevel) ? Math.max(0, bonusPerLevel) : 0;
        double total = basePercent * (1 + Math.max(0, lootingLevel) * bonus);
        return Math.min(100, total);
    }

    /** {@code roll} is in {@code [0, 100)}. */
    static boolean drops(double chancePercent, double roll) {
        if (!(chancePercent > 0)) {
            return false;
        }
        if (chancePercent >= 100) {
            return true;
        }
        return roll < chancePercent;
    }
}
