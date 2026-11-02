package dev.yanianz.atrium.module.mobs;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HeadDropRulesTest {
    @Test void lootingRaisesTheBaseChance() {
        assertEquals(5, HeadDropRules.chance(5, 0, 0.02));
        assertEquals(5.5, HeadDropRules.chance(5, 5, 0.02));
        assertEquals(10, HeadDropRules.chance(5, 10, 0.1));
    }

    @Test void chanceIsClampedAtOneHundred() {
        assertEquals(100, HeadDropRules.chance(90, 10, 0.5));
    }

    @Test void uselessInputsGiveNoDrop() {
        assertEquals(0, HeadDropRules.chance(0, 3, 0.5));
        assertEquals(0, HeadDropRules.chance(-5, 3, 0.5));
        assertEquals(0, HeadDropRules.chance(Double.NaN, 3, 0.5));
        assertEquals(5, HeadDropRules.chance(5, -3, -1));
        assertEquals(5, HeadDropRules.chance(5, 3, Double.NaN));
    }

    @Test void rollIsStrict() {
        assertFalse(HeadDropRules.drops(0, 0));
        assertTrue(HeadDropRules.drops(100, 99.99));
        assertTrue(HeadDropRules.drops(5, 4.99));
        assertFalse(HeadDropRules.drops(5, 5));
    }
}
