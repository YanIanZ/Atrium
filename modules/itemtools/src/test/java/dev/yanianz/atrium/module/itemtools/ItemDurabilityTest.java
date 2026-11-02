package dev.yanianz.atrium.module.itemtools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ItemDurabilityTest {
    @Test
    void percentageRoundsDownAndIsCappedAtOneHundred() {
        assertEquals(0, ItemDurability.percentRemaining(1000, 0));
        assertEquals(9, ItemDurability.percentRemaining(1000, 99));
        assertEquals(10, ItemDurability.percentRemaining(1000, 100));
        assertEquals(100, ItemDurability.percentRemaining(1000, 1000));
        assertEquals(100, ItemDurability.percentRemaining(1000, 5000));
        assertEquals(0, ItemDurability.percentRemaining(0, 10));
    }

    @Test
    void warnsOnlyWhenTheItemSurvivesTheHitAndIsBelowTheThreshold() {
        assertTrue(ItemDurability.shouldWarn(1000, 100, 10));
        assertTrue(ItemDurability.shouldWarn(1000, 1, 10));
        assertFalse(ItemDurability.shouldWarn(1000, 0, 10), "a breaking item has its own event");
        assertFalse(ItemDurability.shouldWarn(1000, 500, 10));
        assertFalse(ItemDurability.shouldWarn(0, 5, 10), "items without durability never warn");
        assertFalse(ItemDurability.shouldWarn(1000, 100, 0), "a zero threshold is raised to one percent");
    }

    @Test
    void thresholdIsClamped() {
        assertEquals(1, ItemDurability.threshold(-20));
        assertEquals(90, ItemDurability.threshold(500));
        assertEquals(25, ItemDurability.threshold(25));
        assertFalse(ItemDurability.shouldWarn(100, 100, 150), "the threshold never exceeds 90 percent");
        assertTrue(ItemDurability.shouldWarn(100, 90, 150));
    }
}
