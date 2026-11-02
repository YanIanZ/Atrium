package dev.yanianz.atrium.module.itemtools;

/**
 * Pure decision for the opt-in low-durability warning. A damageable item has {@code maxDurability > 0};
 * the warning fires when the damage about to be applied would leave the item at or below the configured
 * percentage, but only while the item still exists (a broken item has its own event and message).
 */
final class ItemDurability {
    static final int MAX_THRESHOLD_PERCENT = 90;

    private ItemDurability() {
    }

    static int threshold(int configured) {
        return Math.clamp(configured, 1, MAX_THRESHOLD_PERCENT);
    }

    /** Remaining durability as a whole percentage, rounded down; 0 for an item without durability. */
    static int percentRemaining(int maxDurability, int remaining) {
        if (maxDurability <= 0 || remaining <= 0) {
            return 0;
        }
        if (remaining >= maxDurability) {
            return 100;
        }
        return (int) ((long) remaining * 100L / maxDurability);
    }

    static boolean shouldWarn(int maxDurability, int remainingAfterDamage, int thresholdPercent) {
        if (maxDurability <= 0 || remainingAfterDamage <= 0) {
            return false;
        }
        return percentRemaining(maxDurability, remainingAfterDamage) <= threshold(thresholdPercent);
    }
}
