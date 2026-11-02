package dev.yanianz.atrium.module.playtime;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PlaytimePeriodTest {
    private final LocalDate wednesday = LocalDate.of(2026, 11, 4);

    @Test void parsesOnlyKnownPeriodsCaseInsensitively() {
        assertEquals(PlaytimePeriod.DAY, PlaytimePeriod.parse("day").orElseThrow());
        assertEquals(PlaytimePeriod.WEEK, PlaytimePeriod.parse(" WEEK ").orElseThrow());
        assertEquals(PlaytimePeriod.MONTH, PlaytimePeriod.parse("Month").orElseThrow());
        assertEquals(PlaytimePeriod.ALL, PlaytimePeriod.parse("all").orElseThrow());
        assertTrue(PlaytimePeriod.parse("year").isEmpty());
        assertTrue(PlaytimePeriod.parse(null).isEmpty());
    }

    @Test void computesInclusiveCalendarBoundaries() {
        assertEquals(wednesday, PlaytimePeriod.DAY.start(wednesday));
        assertEquals(LocalDate.of(2026, 11, 2), PlaytimePeriod.WEEK.start(wednesday));
        assertEquals(LocalDate.of(2026, 11, 1), PlaytimePeriod.MONTH.start(wednesday));
        assertNull(PlaytimePeriod.ALL.start(wednesday));
    }

    @Test void weekStartsOnMondayEvenOnSunday() {
        assertEquals(LocalDate.of(2026, 11, 2), PlaytimePeriod.WEEK.start(LocalDate.of(2026, 11, 8)));
    }
}
