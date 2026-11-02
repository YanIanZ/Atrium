package dev.yanianz.atrium.module.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class RequestRulesTest {
    @Test
    void zeroOrNegativeLimitMeansUnlimited() {
        assertTrue(RequestRules.withinDistance(10_000, 0));
        assertTrue(RequestRules.withinDistance(10_000, -1));
    }

    @Test
    void distanceIsInclusive() {
        assertTrue(RequestRules.withinDistance(100, 100));
        assertTrue(RequestRules.withinDistance(0, 100));
        assertFalse(RequestRules.withinDistance(100.5, 100));
    }

    @Test
    void nonFiniteDistancesAreRefused() {
        assertFalse(RequestRules.withinDistance(Double.NaN, 100));
        assertFalse(RequestRules.withinDistance(Double.POSITIVE_INFINITY, 100));
    }

    @Test
    void blockedItemsUseTheSharedPatternMatcher() {
        assertTrue(RequestRules.blockedItem(List.of("ENDER_PEARL"), "ENDER_PEARL"));
        assertTrue(RequestRules.blockedItem(List.of("*_PEARL"), "ENDER_PEARL"));
        assertFalse(RequestRules.blockedItem(List.of(), "ENDER_PEARL"));
        assertFalse(RequestRules.blockedItem(List.of("ENDER_PEARL"), "STONE"));
    }

    @Test
    void launchpadPowerIsPerBlockType() {
        Map<String, Double> pads = Map.of(
                "STONE_PRESSURE_PLATE", 1.5,
                "LIGHT_WEIGHTED_PRESSURE_PLATE", 1.2);
        assertEquals(OptionalDouble.of(1.5), Launchpads.power(pads, "stone_pressure_plate"));
        assertEquals(OptionalDouble.of(1.2), Launchpads.power(pads, "LIGHT_WEIGHTED_PRESSURE_PLATE"));
        assertTrue(Launchpads.power(pads, "OAK_PRESSURE_PLATE").isEmpty());
        assertTrue(Launchpads.power(Map.of("STONE_PRESSURE_PLATE", 0.0), "STONE_PRESSURE_PLATE").isEmpty());
    }
}
