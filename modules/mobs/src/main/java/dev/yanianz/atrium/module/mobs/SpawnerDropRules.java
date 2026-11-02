package dev.yanianz.atrium.module.mobs;

/**
 * Whether a broken spawner drops its item. Pure: the chance formula (silk-touch gate and clamping) and the
 * roll are unit tested. The roll is compared with {@code <}, never {@code <=}, so a configured chance of 0
 * really means never and 100 really means always.
 */
final class SpawnerDropRules {
    private SpawnerDropRules() {}

    /** Drop chance in percent after the silk-touch gate; {@code 0} when silk touch is required but missing. */
    static double chance(double basePercent, boolean silkTouch, boolean requiresSilkTouch) {
        if (requiresSilkTouch && !silkTouch) {
            return 0;
        }
        if (!Double.isFinite(basePercent)) {
            return 0;
        }
        return Math.clamp(basePercent, 0, 100);
    }

    /** {@code roll} is in {@code [0, 100)}; a chance of 100 always drops, 0 never drops. */
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
