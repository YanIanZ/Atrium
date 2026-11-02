package dev.yanianz.atrium.module.placeholders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class StatisticsPlaceholdersTest {
    @Test
    void onlyStatisticKeysAreHandled() {
        assertTrue(StatisticsPlaceholders.handles("stat_walk_one_cm"));
        assertTrue(StatisticsPlaceholders.handles("statistic_deaths"));
        assertFalse(StatisticsPlaceholders.handles("balance"));
        assertFalse(StatisticsPlaceholders.handles(null));
    }

    @Test
    void rawAndFormattedValuesAreReadFromTheCache() {
        Map<String, Long> values = Map.of("walk_one_cm", 1_234L, "play_one_minute", 600L);
        assertEquals("1234", StatisticsPlaceholders.resolve("stat_walk_one_cm", values, "-"));
        assertEquals("12.3 m", StatisticsPlaceholders.resolve("stat_walk_one_cm_formatted", values, "-"));
        assertEquals("30s", StatisticsPlaceholders.resolve("stat_play_one_minute_formatted", values, "-"));
        assertEquals("-", StatisticsPlaceholders.resolve("stat_deaths", values, "-"));
        assertEquals("-", StatisticsPlaceholders.resolve("stat_no_such_thing", values, "-"));
        assertEquals("-", StatisticsPlaceholders.resolve("stat_walk_one_cm", Map.of(), "-"));
    }

    @Test
    void theStatisticPrefixIsOptional() {
        Map<String, Long> values = Map.of("deaths", 3L);
        assertEquals("3", StatisticsPlaceholders.resolve("statistic_deaths", values, "-"));
        assertEquals("3", StatisticsPlaceholders.resolve("stat_deaths", values, "-"));
    }
}
