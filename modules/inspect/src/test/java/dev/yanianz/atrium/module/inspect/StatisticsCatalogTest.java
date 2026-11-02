package dev.yanianz.atrium.module.inspect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.statistics.StatisticsCatalog;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StatisticsCatalogTest {
    @Test
    void keysAreStableAndCaseInsensitive() {
        Optional<StatisticsCatalog.Entry> walk = StatisticsCatalog.byKey("WALK_ONE_CM");
        assertTrue(walk.isPresent());
        assertEquals("walk_one_cm", walk.get().key());
        assertEquals(walk, StatisticsCatalog.byKey("stat_walk_one_cm"));
        assertEquals(walk, StatisticsCatalog.byKey("atrium_statistic_walk_one_cm"));
        assertTrue(StatisticsCatalog.byKey("no_such_statistic").isEmpty());
        assertTrue(StatisticsCatalog.byKey(null).isEmpty());
    }

    @Test
    void distanceAndTimeHaveTheirOwnUnits() {
        StatisticsCatalog.Entry walk = StatisticsCatalog.byKey("walk_one_cm").orElseThrow();
        assertEquals(StatisticsCatalog.Kind.DISTANCE, walk.kind());
        assertEquals("12.3 m", StatisticsCatalog.format(walk, 1_234L));
        assertEquals("1.50 km", StatisticsCatalog.format(walk, 150_000L));

        StatisticsCatalog.Entry play = StatisticsCatalog.byKey("play_one_minute").orElseThrow();
        assertEquals(StatisticsCatalog.Kind.TIME, play.kind());
        assertEquals("30s", StatisticsCatalog.format(play, 600L));
        assertEquals("1h 1m", StatisticsCatalog.format(play, 3_660L * 20L));
        assertEquals("1d", StatisticsCatalog.format(play, 86_400L * 20L));
    }

    @Test
    void subtypedStatisticsAreMarked() {
        assertTrue(StatisticsCatalog.byKey("mine_block").orElseThrow().subtyped());
        assertTrue(StatisticsCatalog.byKey("kill_entity").orElseThrow().subtyped());
        assertEquals(false, StatisticsCatalog.byKey("deaths").orElseThrow().subtyped());
    }

    @Test
    void catalogCoversTheRunningBukkitStatistics() {
        assertEquals(org.bukkit.Statistic.values().length, StatisticsCatalog.entries().size());
        for (StatisticsCatalog.Entry entry : StatisticsCatalog.entries()) {
            assertEquals(StatisticsCatalog.keyOf(entry.statistic()), entry.key());
        }
    }
}
