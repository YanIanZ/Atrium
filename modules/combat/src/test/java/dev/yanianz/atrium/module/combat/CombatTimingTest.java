package dev.yanianz.atrium.module.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CombatTimingTest {
    @Test
    void remainingNeverGoesNegative() {
        assertEquals(5000L, CombatTiming.remaining(1000L, 6000L));
        assertEquals(0L, CombatTiming.remaining(6000L, 6000L));
        assertEquals(0L, CombatTiming.remaining(7000L, 6000L));
    }

    @Test
    void aNewHitExtendsButNeverShortensTheTag() {
        assertEquals(11_000L, CombatTiming.extendedUntil(10_000L, 1_000L, 10_000L));
        assertEquals(11_000L, CombatTiming.extendedUntil(11_000L, 1_000L, 10_000L));
        assertEquals(20_000L, CombatTiming.extendedUntil(0L, 5_000L, 15_000L));
        assertEquals(5_000L, CombatTiming.extendedUntil(0L, 5_000L, 0L));
        assertEquals(5_000L, CombatTiming.extendedUntil(0L, 5_000L, -1000L));
    }

    @Test
    void secondsRoundUpAndNeverShowZeroWhileRunning() {
        assertEquals(0, CombatTiming.secondsLeft(0L));
        assertEquals(0, CombatTiming.secondsLeft(-10L));
        assertEquals(1, CombatTiming.secondsLeft(1L));
        assertEquals(1, CombatTiming.secondsLeft(1000L));
        assertEquals(2, CombatTiming.secondsLeft(1001L));
        assertEquals(15, CombatTiming.secondsLeft(15_000L));
    }

    @Test
    void progressStaysInsideTheBar() {
        assertEquals(0.5f, CombatTiming.progress(5_000L, 10_000L), 0.0001f);
        assertEquals(1.0f, CombatTiming.progress(10_000L, 10_000L), 0.0001f);
        assertEquals(0.0f, CombatTiming.progress(0L, 10_000L), 0.0001f);
        assertEquals(0.0f, CombatTiming.progress(5_000L, 0L), 0.0001f);
        assertEquals(0.0f, CombatTiming.progress(-1L, 10_000L), 0.0001f);
        assertEquals(1.0f, CombatTiming.progress(20_000L, 10_000L), 0.0001f);
    }
}
