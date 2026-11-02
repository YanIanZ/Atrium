package dev.yanianz.atrium.module.inspect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.statistics.StatisticsCatalog;
import org.junit.jupiter.api.Test;

class StatsEditTest {
    @Test
    void amountsAreConvertedFromDisplayUnits() {
        assertEquals(20L, StatsEdit.toRaw(1L, StatisticsCatalog.Kind.TIME));
        assertEquals(100L, StatsEdit.toRaw(1L, StatisticsCatalog.Kind.DISTANCE));
        assertEquals(7L, StatsEdit.toRaw(7L, StatisticsCatalog.Kind.COUNT));
        assertEquals(Long.MAX_VALUE, StatsEdit.toRaw(Long.MAX_VALUE, StatisticsCatalog.Kind.TIME));
        assertEquals(0L, StatsEdit.toRaw(-5L, StatisticsCatalog.Kind.COUNT));
    }

    @Test
    void setAddAndTakeStayInsideTheBukkitRange() {
        assertEquals(15, StatsEdit.apply(10, StatsEdit.Mode.SET, 15L));
        assertEquals(25, StatsEdit.apply(10, StatsEdit.Mode.ADD, 15L));
        assertEquals(0, StatsEdit.apply(10, StatsEdit.Mode.TAKE, 15L));
        assertEquals(4, StatsEdit.apply(10, StatsEdit.Mode.TAKE, 6L));
        assertEquals(Integer.MAX_VALUE, StatsEdit.apply(Integer.MAX_VALUE - 1, StatsEdit.Mode.ADD, 10L));
        assertEquals(Integer.MAX_VALUE, StatsEdit.apply(0, StatsEdit.Mode.SET, Long.MAX_VALUE));
        assertEquals(0, StatsEdit.apply(-4, StatsEdit.Mode.SET, -1L));
    }

    @Test
    void takeClampsAndAddComputesRawUnits() {
        assertEquals(0, StatsEdit.apply(1, StatsEdit.Mode.TAKE, 5L));
        assertEquals(5, StatsEdit.apply(20, StatsEdit.Mode.TAKE, 15L));
        assertEquals(30, StatsEdit.apply(10, StatsEdit.Mode.ADD, 1L, StatisticsCatalog.Kind.TIME));
        assertEquals(110, StatsEdit.apply(10, StatsEdit.Mode.ADD, 1L, StatisticsCatalog.Kind.DISTANCE));
    }

    @Test
    void modeParsingIsCaseInsensitive() {
        assertEquals(StatsEdit.Mode.ADD, StatsEdit.Mode.parse(" Add ").orElseThrow());
        assertEquals(StatsEdit.Mode.TAKE, StatsEdit.Mode.parse("take").orElseThrow());
        assertTrue(StatsEdit.Mode.parse("multiply").isEmpty());
        assertTrue(StatsEdit.Mode.parse(null).isEmpty());
    }
}
