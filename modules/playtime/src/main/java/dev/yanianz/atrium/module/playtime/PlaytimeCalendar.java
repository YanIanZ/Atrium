package dev.yanianz.atrium.module.playtime;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Day grid of the {@code /cplaytime} calendar. Always six weeks so the menu has a stable size; days before
 * and after the month are included (marked {@code inMonth == false}) and are shown dimmed.
 */
final class PlaytimeCalendar {
    static final int WEEKS = 6;
    static final int COLUMNS = 7;
    static final int CELLS = WEEKS * COLUMNS;

    record Cell(LocalDate date, boolean inMonth) {}

    private PlaytimeCalendar() {}

    static Optional<LocalDate> parseMonth(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            YearMonth month = YearMonth.parse(raw.strip(), DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT));
            return Optional.of(month.atDay(1));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    static LocalDate previousMonth(LocalDate month) {
        return month.withDayOfMonth(1).minusMonths(1);
    }

    static LocalDate nextMonth(LocalDate month) {
        return month.withDayOfMonth(1).plusMonths(1);
    }

    static List<Cell> cells(LocalDate month, DayOfWeek firstDay) {
        LocalDate first = month.withDayOfMonth(1);
        int offset = Math.floorMod(first.getDayOfWeek().getValue() - firstDay.getValue(), 7);
        LocalDate start = first.minusDays(offset);
        List<Cell> cells = new ArrayList<>(CELLS);
        for (int i = 0; i < CELLS; i++) {
            LocalDate date = start.plusDays(i);
            cells.add(new Cell(date, date.getMonth() == first.getMonth() && date.getYear() == first.getYear()));
        }
        return List.copyOf(cells);
    }

    /** First day of the week from config; unknown values fall back to Monday. */
    static DayOfWeek firstDay(String configured) {
        if (configured == null) {
            return DayOfWeek.MONDAY;
        }
        try {
            return DayOfWeek.valueOf(configured.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DayOfWeek.MONDAY;
        }
    }

    /** e.g. {@code November 2026}, in the viewer's language. */
    static String monthTitle(LocalDate month, Locale locale) {
        Locale effective = locale == null ? Locale.ENGLISH : locale;
        return month.getMonth().getDisplayName(TextStyle.FULL, effective) + " " + month.getYear();
    }

    static String dayName(LocalDate date, Locale locale) {
        Locale effective = locale == null ? Locale.ENGLISH : locale;
        return date.getDayOfWeek().getDisplayName(TextStyle.SHORT, effective) + " " + date.getDayOfMonth();
    }

    static String weekdayName(DayOfWeek day, Locale locale) {
        Locale effective = locale == null ? Locale.ENGLISH : locale;
        return day.getDisplayName(TextStyle.SHORT, effective);
    }
}
