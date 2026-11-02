package dev.yanianz.atrium.module.worldrules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Death-handling decisions without any server access, so they are unit-testable. */
final class DeathControl {
    private DeathControl() {}

    /** The world rule or the permission keeps the whole inventory. */
    static boolean keepsInventory(WorldRule rule, boolean permissionEnabled, boolean hasPermission) {
        return rule != null && (rule.keepInventoryOnDeath || (permissionEnabled && hasPermission));
    }

    /** The world rule or the permission keeps the experience level. */
    static boolean keepsExperience(WorldRule rule, boolean permissionEnabled, boolean hasPermission) {
        return rule != null && (rule.keepExperienceOnDeath || (permissionEnabled && hasPermission));
    }

    /**
     * Moves a random subset of {@code drops} into {@code kept} so that only {@code percent} of the stacks
     * still falls on the ground. An empty list, 100 percent and larger values change nothing.
     */
    static <T> void applyDropPercent(List<T> drops, List<T> kept, double percent, Random random) {
        if (drops == null || kept == null || drops.isEmpty()) {
            return;
        }
        double wanted = Double.isFinite(percent) ? Math.max(0.0, Math.min(100.0, percent)) : 100.0;
        if (wanted >= 100.0) {
            return;
        }
        if (wanted <= 0.0) {
            kept.addAll(drops);
            drops.clear();
            return;
        }
        List<T> copy = new ArrayList<>(drops);
        Collections.shuffle(copy, random == null ? new Random() : random);
        int dropCount = (int) Math.round(copy.size() * wanted / 100.0);
        dropCount = Math.max(0, Math.min(copy.size(), dropCount));
        drops.clear();
        drops.addAll(copy.subList(0, dropCount));
        kept.addAll(copy.subList(dropCount, copy.size()));
    }
}
