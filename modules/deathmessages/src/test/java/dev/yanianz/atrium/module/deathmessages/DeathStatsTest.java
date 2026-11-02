package dev.yanianz.atrium.module.deathmessages;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DeathStatsTest {
    @Test
    void ratioTreatsZeroDeathsAsOne() {
        assertEquals(0.0, new DeathStats(0, 0).ratio());
        assertEquals(5.0, new DeathStats(5, 0).ratio());
        assertEquals(0.5, new DeathStats(5, 10).ratio());
    }

    @Test
    void ratioTextUsesLocaleIndependentDecimalPoint() {
        assertEquals("0.50", new DeathStats(5, 10).ratioText());
        assertEquals("2.00", new DeathStats(2, 1).ratioText());
    }
}
