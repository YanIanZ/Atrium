package dev.yanianz.atrium.module.elytra;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ElytraRulesTest {
    @Test
    void riptideGuardBlocksOnlyWhenEnabled() {
        assertTrue(ElytraRules.blocksBoost(true, true, false));
        assertTrue(ElytraRules.blocksBoost(true, false, true));
        assertFalse(ElytraRules.blocksBoost(true, false, false));
        assertFalse(ElytraRules.blocksBoost(false, true, true));
    }

    @Test
    void freeBoostIsAlwaysPayable() {
        assertTrue(ElytraRules.canPay(0, 0, 0, 0));
        assertTrue(ElytraRules.canPay(30, 5000, 0, 0));
    }

    @Test
    void pointsAreChargedAndTheLevelIsAGate() {
        assertTrue(ElytraRules.canPay(2, 30, 2, 20));
        assertFalse(ElytraRules.canPay(1, 30, 2, 20));
        assertFalse(ElytraRules.canPay(2, 19, 2, 20));
        assertTrue(ElytraRules.canPay(0, 5, 0, 5));
        assertFalse(ElytraRules.canPay(0, 4, 0, 5));
        assertTrue(ElytraRules.canPay(0, 0, 0, 0));
    }

    @Test
    void configurationBoundsAreChecked() {
        assertTrue(ElytraRules.validCost(0, 0));
        assertTrue(ElytraRules.validCost(1000, 100_000));
        assertFalse(ElytraRules.validCost(-1, 0));
        assertFalse(ElytraRules.validCost(0, -1));
        assertFalse(ElytraRules.validCost(1001, 0));
        assertFalse(ElytraRules.validCost(0, 100_001));
    }
}
