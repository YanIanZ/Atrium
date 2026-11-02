package dev.yanianz.atrium.module.worldrules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class DeathControlTest {
    @Test
    void worldRuleAndPermissionBothKeepTheInventory() {
        WorldRule rule = new WorldRule();
        assertFalse(DeathControl.keepsInventory(rule, false, true));
        assertFalse(DeathControl.keepsInventory(rule, true, false));
        assertTrue(DeathControl.keepsInventory(rule, true, true));

        rule.keepInventoryOnDeath = true;
        assertTrue(DeathControl.keepsInventory(rule, false, false));
        assertFalse(DeathControl.keepsExperience(rule, false, false));
    }

    @Test
    void experienceFollowsItsOwnFlag() {
        WorldRule rule = new WorldRule();
        rule.keepExperienceOnDeath = true;
        assertTrue(DeathControl.keepsExperience(rule, false, false));
        assertFalse(DeathControl.keepsInventory(rule, false, false));
    }

    @Test
    void fullPercentChangesNothing() {
        List<Integer> drops = new ArrayList<>(List.of(1, 2, 3));
        List<Integer> kept = new ArrayList<>();
        DeathControl.applyDropPercent(drops, kept, 100, new Random(1));
        assertEquals(List.of(1, 2, 3), drops);
        assertTrue(kept.isEmpty());
    }

    @Test
    void zeroPercentKeepsEverything() {
        List<Integer> drops = new ArrayList<>(List.of(1, 2, 3));
        List<Integer> kept = new ArrayList<>();
        DeathControl.applyDropPercent(drops, kept, 0, new Random(1));
        assertTrue(drops.isEmpty());
        assertEquals(3, kept.size());
    }

    @Test
    void partialPercentSplitsTheStacks() {
        List<Integer> drops = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            drops.add(i);
        }
        List<Integer> kept = new ArrayList<>();
        DeathControl.applyDropPercent(drops, kept, 30, new Random(7));
        assertEquals(3, drops.size());
        assertEquals(7, kept.size());
        List<Integer> all = new ArrayList<>(drops);
        all.addAll(kept);
        all.sort(Integer::compareTo);
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), all);
    }

    @Test
    void invalidPercentFallsBackToVanilla() {
        List<Integer> drops = new ArrayList<>(List.of(1, 2));
        List<Integer> kept = new ArrayList<>();
        DeathControl.applyDropPercent(drops, kept, Double.NaN, new Random(1));
        assertEquals(2, drops.size());
        assertTrue(kept.isEmpty());
    }

    @Test
    void emptyDropsStayEmpty() {
        List<Integer> drops = new ArrayList<>();
        List<Integer> kept = new ArrayList<>();
        DeathControl.applyDropPercent(drops, kept, 50, new Random(1));
        assertTrue(drops.isEmpty());
        assertTrue(kept.isEmpty());
    }
}
