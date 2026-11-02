package dev.yanianz.atrium.module.seating;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Pure /ride limits. */
class RideRulesTest {
    @Test void ownEntityAlwaysRideable() {
        assertEquals(RideRules.Verdict.OK,
                RideRules.check(false, true, 0, 3, true, false));
    }

    @Test void passengersAreCapped() {
        assertEquals(RideRules.Verdict.FULL, RideRules.check(false, true, 3, 3, true, false));
        assertEquals(RideRules.Verdict.OK, RideRules.check(false, true, 2, 3, true, false));
        // A broken cap can never disable the check entirely.
        assertEquals(RideRules.Verdict.FULL, RideRules.check(false, true, 1, 0, true, false));
    }

    @Test void playersNeedTheConfigSwitchAndTheRightType() {
        assertEquals(RideRules.Verdict.PLAYERS_DISABLED, RideRules.check(true, false, 0, 3, true, false));
        assertEquals(RideRules.Verdict.OK, RideRules.check(true, true, 0, 3, true, false));
        assertEquals(RideRules.Verdict.UNSUITABLE, RideRules.check(false, true, 0, 3, false, false));
        assertEquals(RideRules.Verdict.SELF, RideRules.check(true, true, 0, 3, true, true));
    }

    @Test void launchPowerIsClamped() {
        assertEquals(RideRules.MIN_LAUNCH, RideRules.launchPower(-5));
        assertEquals(RideRules.MAX_LAUNCH, RideRules.launchPower(99));
        assertEquals(1.5, RideRules.launchPower(1.5));
        assertEquals(RideRules.MIN_LAUNCH, RideRules.launchPower(Double.NaN));
        assertEquals(RideRules.MAX_LAUNCH, RideRules.launchPower(Double.POSITIVE_INFINITY));
    }

    @Test void launchPowerParsingRejectsGarbage() {
        assertEquals(1.25, RideRules.parsePower(" 1.25 "));
        assertNull(RideRules.parsePower("high"));
        assertNull(RideRules.parsePower(""));
        assertNull(RideRules.parsePower(null));
    }

    @Test void rescueNeedsFreeFeetAndHeadAboveGround() {
        assertFalse(RideRules.needsRescue(true, true, true));
        assertTrue(RideRules.needsRescue(false, true, true));
        assertTrue(RideRules.needsRescue(true, false, true));
        assertTrue(RideRules.needsRescue(true, true, false));
    }
}
