package dev.yanianz.atrium.module.playerplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TflyMathTest {
    private static final long SECOND = 1_000L;

    @Test
    void countsDownOnlyWhileOnline() {
        long stored = 60 * SECOND;
        long onlineSince = 10_000;
        long now = 25_000;
        assertEquals(45 * SECOND, TflyMath.remaining(stored, onlineSince, now));
    }

    @Test
    void resumingUsesThePersistedValueWithoutOfflineTime() {
        long stored = 60 * SECOND;
        long now = 999_999;
        // A resumed session captures "online since now", so nothing was spent while offline.
        assertEquals(stored, TflyMath.remaining(stored, now, now));
    }

    @Test
    void countdownStopsAtZero() {
        assertEquals(0, TflyMath.remaining(5 * SECOND, 0, 60 * SECOND));
    }

    @Test
    void aClockMovingBackwardsNeverExtendsTheCountdown() {
        assertEquals(30 * SECOND, TflyMath.remaining(30 * SECOND, 100_000, 50_000));
    }

    @Test
    void warningFiresOnceInTheLastSeconds() {
        assertFalse(TflyMath.shouldWarn(11_000, false, 10_000));
        assertTrue(TflyMath.shouldWarn(10_000, false, 10_000));
        assertTrue(TflyMath.shouldWarn(1_000, false, 10_000));
        assertFalse(TflyMath.shouldWarn(1_000, true, 10_000));
        assertFalse(TflyMath.shouldWarn(0, false, 10_000));
    }

    @Test
    void extendingStacksAndCaps() {
        assertEquals(90 * SECOND, TflyMath.extend(30 * SECOND, 60 * SECOND, 90 * SECOND));
        assertEquals(90 * SECOND, TflyMath.extend(80 * SECOND, 60 * SECOND, 90 * SECOND));
        assertEquals(Long.MAX_VALUE, TflyMath.extend(Long.MAX_VALUE - 5, 100, Long.MAX_VALUE));
    }

    @Test
    void ceilSecondsRoundsUp() {
        assertEquals(1, TflyMath.ceilSeconds(Duration.ofMillis(1)));
        assertEquals(2, TflyMath.ceilSeconds(Duration.ofMillis(1_001)));
        assertEquals(0, TflyMath.ceilSeconds(Duration.ZERO));
    }
}
