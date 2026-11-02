package dev.yanianz.atrium.module.playtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class PlaytimeCalendarTest {
    @Test
    void parsesMonthInput() {
        assertEquals(LocalDate.of(2026, 11, 1), PlaytimeCalendar.parseMonth("2026-11").orElseThrow());
        assertTrue(PlaytimeCalendar.parseMonth("2026-13").isEmpty());
        assertTrue(PlaytimeCalendar.parseMonth("November").isEmpty());
        assertTrue(PlaytimeCalendar.parseMonth(null).isEmpty());
    }

    @Test
    void gridStartsOnTheConfiguredWeekdayAndCoversSixWeeks() {
        List<PlaytimeCalendar.Cell> cells = PlaytimeCalendar.cells(LocalDate.of(2026, 11, 1), DayOfWeek.MONDAY);
        assertEquals(42, cells.size());
        assertEquals(DayOfWeek.MONDAY, cells.getFirst().date().getDayOfWeek());
        assertEquals(LocalDate.of(2026, 10, 26), cells.getFirst().date());
        assertEquals(LocalDate.of(2026, 12, 6), cells.getLast().date());
        long inMonth = cells.stream().filter(PlaytimeCalendar.Cell::inMonth).count();
        assertEquals(30, inMonth);
        assertFalse(cells.getFirst().inMonth());
        assertTrue(cells.stream().anyMatch(cell -> cell.date().equals(LocalDate.of(2026, 11, 30)) && cell.inMonth()));
    }

    @Test
    void sundayFirstShiftsTheGrid() {
        List<PlaytimeCalendar.Cell> cells = PlaytimeCalendar.cells(LocalDate.of(2026, 11, 1), DayOfWeek.SUNDAY);
        assertEquals(DayOfWeek.SUNDAY, cells.getFirst().date().getDayOfWeek());
        assertEquals(LocalDate.of(2026, 11, 1), cells.getFirst().date());
    }

    @Test
    void monthNavigationSkipsToTheFirstDay() {
        LocalDate month = LocalDate.of(2026, 1, 17);
        assertEquals(LocalDate.of(2025, 12, 1), PlaytimeCalendar.previousMonth(month));
        assertEquals(LocalDate.of(2026, 2, 1), PlaytimeCalendar.nextMonth(month));
    }

    @Test
    void titlesUseTheGivenLocale() {
        assertEquals("November 2026", PlaytimeCalendar.monthTitle(LocalDate.of(2026, 11, 1), Locale.ENGLISH));
        assertEquals("MONDAY", PlaytimeCalendar.firstDay("monday").name());
        assertEquals("MONDAY", PlaytimeCalendar.firstDay("nonsense").name());
        assertEquals("SUNDAY", PlaytimeCalendar.firstDay("Sunday").name());
    }
}
