package dev.yanianz.atrium.module.worldrules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TimeRateMathTest {
    @Test
    void vanillaMultiplierAddsNothing() {
        assertEquals(0L, TimeRateMath.extraTicks(100, 1.0));
    }

    @Test
    void fasterAndSlowerMultipliersScaleWithTheInterval() {
        assertEquals(100L, TimeRateMath.extraTicks(100, 2.0));
        assertEquals(-50L, TimeRateMath.extraTicks(100, 0.5));
        assertEquals(-100L, TimeRateMath.extraTicks(100, 0.0));
        assertEquals(0L, TimeRateMath.extraTicks(0, 2.0));
    }

    @Test
    void invalidMultipliersFallBackToVanilla() {
        assertEquals(1.0, TimeRateMath.clampMultiplier(Double.NaN));
        assertEquals(1.0, TimeRateMath.clampMultiplier(Double.POSITIVE_INFINITY));
        assertEquals(0.0, TimeRateMath.clampMultiplier(-2));
        assertEquals(100.0, TimeRateMath.clampMultiplier(1000));
        assertEquals(0L, TimeRateMath.extraTicks(100, Double.NaN));
    }

    @Test
    void timeWrapsInsideOneDay() {
        assertEquals(5L, TimeRateMath.advance(23990L, 15L));
        assertEquals(23990L, TimeRateMath.advance(5L, -15L));
    }
}
