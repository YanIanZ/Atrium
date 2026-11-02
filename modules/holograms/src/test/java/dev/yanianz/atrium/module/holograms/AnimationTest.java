package dev.yanianz.atrium.module.holograms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AnimationTest {
    private static final float EPS = 1e-4f;

    @Test
    void aFadeGrowsFromAlmostZeroToFullSize() {
        assertEquals(Animation.MIN_FADE, Animation.fade(0, 20), EPS);
        assertTrue(Animation.fade(5, 20) > Animation.fade(4, 20));
        assertEquals(1f, Animation.fade(20, 20), EPS);
        assertEquals(1f, Animation.fade(200, 20), EPS);
    }

    @Test
    void aDisabledFadeIsAlwaysFullSize() {
        assertEquals(1f, Animation.fade(0, 0), EPS);
        assertEquals(1f, Animation.fade(3, -5), EPS);
    }

    @Test
    void theFadeEasesInAndOut() {
        // Smoothstep: the middle of the fade is exactly halfway, the edges move slower.
        assertEquals((Animation.MIN_FADE + 1f) / 2f, Animation.fade(10, 20), EPS);
        float quarter = Animation.fade(5, 20);
        float middle = Animation.fade(10, 20);
        assertTrue(middle - quarter > quarter - Animation.MIN_FADE);
    }

    @Test
    void yawAdvancesWithTicksAndWraps() {
        assertEquals(10f, Animation.yaw(0f, 10f, 20), EPS);
        assertEquals(180f, Animation.yaw(0f, 90f, 40), EPS);
        assertEquals(0f, Animation.yaw(350f, 10f, 20), EPS);
        // 10 degrees per second for 40 ticks (2 s) from 350 wraps through 360 back to 10.
        assertEquals(10f, Animation.yaw(350f, 10f, 40), EPS);
    }

    @Test
    void aNegativeSpeedTurnsTheOtherWay() {
        assertEquals(350f, Animation.yaw(0f, -10f, 20), EPS);
        assertEquals(180f, Animation.yaw(0f, -180f, 20), EPS);
    }

    @Test
    void fadingStopsAfterTheConfiguredTicks() {
        assertTrue(Animation.fading(5, 0, 10));
        assertFalse(Animation.fading(10, 0, 10));
        assertFalse(Animation.fading(5, 0, 0));
    }
}
