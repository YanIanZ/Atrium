package dev.yanianz.atrium.module.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ServerListCountsTest {
    private static PerformanceConfig.ServerList.Counts counts() {
        return new PerformanceConfig.ServerList.Counts();
    }

    @Test
    void offsetsApplyToTheReportedNumbers() {
        PerformanceConfig.ServerList.Counts counts = counts();
        counts.onlineOffset = 5;
        assertEquals(25, ServerListListener.resolvedOnline(20, 0, counts));
    }

    @Test
    void hidingVanishedPlayersLowersTheCount() {
        assertEquals(18, ServerListListener.resolvedOnline(20, 2, counts()));
    }

    @Test
    void anOverrideWinsOverOffsetsAndHiddenPlayers() {
        PerformanceConfig.ServerList.Counts counts = counts();
        counts.onlineOverride = 3;
        counts.onlineOffset = 10;
        assertEquals(3, ServerListListener.resolvedOnline(20, 5, counts));
    }

    @Test
    void theCountNeverBecomesNegative() {
        assertEquals(0, ServerListListener.resolvedOnline(1, 5, counts()));
    }

    @Test
    void theSessionMaxWinsOverTheServerCapAndTheOverrideWinsOverBoth() {
        PerformanceConfig.ServerList.Counts counts = counts();
        assertEquals(50, ServerListListener.resolveMax(100, 50, counts));
        counts.maxOverride = 7;
        assertEquals(7, ServerListListener.resolveMax(100, 50, counts));
        assertEquals(1, ServerListListener.resolveMax(100, 50, countsWithOffset(-1000)));
    }

    private static PerformanceConfig.ServerList.Counts countsWithOffset(int offset) {
        PerformanceConfig.ServerList.Counts counts = counts();
        counts.maxOffset = offset;
        return counts;
    }

    @Test
    void valuesFromTheFileAreClamped() {
        PerformanceConfig.ServerList.Counts counts = counts();
        counts.onlineOverride = 999_999;
        assertEquals(10_000, counts.onlineOverride());
        counts.maxOverride = 0;
        assertEquals(1, counts.maxOverride());
        counts.onlineOffset = -999_999;
        assertEquals(-1000, counts.onlineOffset());
    }
}
