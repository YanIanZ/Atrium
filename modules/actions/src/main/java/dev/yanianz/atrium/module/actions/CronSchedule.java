package dev.yanianz.atrium.module.actions;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.util.BitSet;
import java.util.Locale;
import java.util.Optional;

/**
 * A five-field cron expression ({@code minute hour day-of-month month day-of-week}) for the schedule engine.
 *
 * <p>Supported syntax per field: {@code *} (any), a number, {@code a-b}, a step {@code *}{@code /n} or
 * {@code a-b/n}, and comma-separated lists. Month and weekday names are accepted ({@code JAN},
 * {@code MON}, ...). Weekday 0 and 7 are both Sunday. When both day-of-month and day-of-week are restricted,
 * a day matches when <em>either</em> matches (classic cron); otherwise both must match.
 *
 * <p>Pure: no server and no clock, so {@link #next(ZonedDateTime)} is fully unit tested. A next time is always
 * strictly in the future, which is what keeps a missed run from being replayed after a restart or lag.
 */
final class CronSchedule {
    /** Roughly five years of minutes: a guard against an expression that can never match (e.g. 31 February). */
    private static final int MAX_STEPS = 5 * 366 * 24 * 4;

    private static final String[] MONTHS = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT",
            "NOV", "DEC"};
    private static final String[] DAYS = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};

    private final BitSet minutes;
    private final BitSet hours;
    private final BitSet daysOfMonth;
    private final BitSet months;
    private final BitSet daysOfWeek;
    private final boolean dayOfMonthRestricted;
    private final boolean dayOfWeekRestricted;
    private final String expression;

    private CronSchedule(BitSet minutes, BitSet hours, BitSet daysOfMonth, BitSet months, BitSet daysOfWeek,
            boolean dayOfMonthRestricted, boolean dayOfWeekRestricted, String expression) {
        this.minutes = minutes;
        this.hours = hours;
        this.daysOfMonth = daysOfMonth;
        this.months = months;
        this.daysOfWeek = daysOfWeek;
        this.dayOfMonthRestricted = dayOfMonthRestricted;
        this.dayOfWeekRestricted = dayOfWeekRestricted;
        this.expression = expression;
    }

    /** Parses an expression; throws {@link IllegalArgumentException} with a readable reason when invalid. */
    static CronSchedule parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("cron expression is empty");
        }
        String[] fields = text.strip().split("\\s+");
        if (fields.length != 5) {
            throw new IllegalArgumentException("cron needs five fields: minute hour day-of-month month day-of-week");
        }
        BitSet minutes = field(fields[0], 0, 59, null, "minute");
        BitSet hours = field(fields[1], 0, 23, null, "hour");
        BitSet daysOfMonth = field(fields[2], 1, 31, null, "day-of-month");
        BitSet months = field(fields[3], 1, 12, MONTHS, "month");
        BitSet daysOfWeek = field(fields[4], 0, 7, DAYS, "day-of-week");
        // Treat 7 as Sunday as well.
        if (daysOfWeek.get(7)) {
            daysOfWeek.set(0);
            daysOfWeek.clear(7);
        }
        return new CronSchedule(minutes, hours, daysOfMonth, months, daysOfWeek,
                !fields[2].equals("*"), !fields[4].equals("*"), text.strip());
    }

    private static BitSet field(String text, int min, int max, String[] names, String label) {
        BitSet bits = new BitSet(max + 1);
        for (String part : text.split(",", -1)) {
            String value = part.strip();
            if (value.isEmpty()) {
                throw new IllegalArgumentException(label + ": empty list entry");
            }
            int step = 1;
            int slash = value.indexOf('/');
            if (slash >= 0) {
                String stepText = value.substring(slash + 1);
                value = value.substring(0, slash);
                try {
                    step = Integer.parseInt(stepText);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(label + ": bad step '" + stepText + "'");
                }
                if (step < 1 || step > max) {
                    throw new IllegalArgumentException(label + ": step must be 1-" + max);
                }
            }
            int from;
            int to;
            if (value.equals("*")) {
                from = min;
                to = max;
            } else {
                int dash = value.indexOf('-');
                if (dash > 0) {
                    from = number(value.substring(0, dash), min, max, names, label);
                    to = number(value.substring(dash + 1), min, max, names, label);
                    if (from > to) {
                        throw new IllegalArgumentException(label + ": range starts after it ends");
                    }
                } else {
                    from = number(value, min, max, names, label);
                    to = slash >= 0 ? max : from;
                }
            }
            for (int i = from; i <= to; i += step) {
                bits.set(i);
            }
        }
        if (bits.isEmpty()) {
            throw new IllegalArgumentException(label + ": matches nothing");
        }
        return bits;
    }

    private static int number(String text, int min, int max, String[] names, String label) {
        String value = text.strip().toUpperCase(Locale.ROOT);
        if (names != null) {
            for (int i = 0; i < names.length; i++) {
                if (names[i].equals(value)) {
                    // Months are 1-based, weekdays 0-based; both name tables start at their minimum.
                    return min + i;
                }
            }
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) {
                throw new IllegalArgumentException(label + ": " + parsed + " is outside " + min + "-" + max);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + ": '" + text + "' is not a number");
        }
    }

    /** The first matching minute strictly after {@code after}, or empty when it never matches. */
    Optional<ZonedDateTime> next(ZonedDateTime after) {
        ZonedDateTime time = after.plusMinutes(1).withSecond(0).withNano(0);
        for (int step = 0; step < MAX_STEPS; step++) {
            if (!months.get(time.getMonthValue())) {
                time = time.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
                continue;
            }
            if (!dayMatches(time)) {
                time = time.plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
                continue;
            }
            if (!hours.get(time.getHour())) {
                time = time.plusHours(1).withMinute(0).withSecond(0).withNano(0);
                continue;
            }
            if (!minutes.get(time.getMinute())) {
                time = time.plusMinutes(1);
                continue;
            }
            return Optional.of(time);
        }
        return Optional.empty();
    }

    private boolean dayMatches(ZonedDateTime time) {
        boolean dom = daysOfMonth.get(time.getDayOfMonth());
        boolean dow = daysOfWeek.get(time.getDayOfWeek().getValue() % 7);
        if (dayOfMonthRestricted && dayOfWeekRestricted) {
            return dom || dow;
        }
        return dom && dow;
    }

    /** Whether the minute of {@code time} itself matches (used by tests and diagnostics). */
    boolean matches(ZonedDateTime time) {
        return months.get(time.getMonthValue()) && dayMatches(time) && hours.get(time.getHour())
                && minutes.get(time.getMinute());
    }

    String expression() {
        return expression;
    }

    /** Weekday number used by the expression, for diagnostics. */
    static int weekday(DayOfWeek day) {
        return day.getValue() % 7;
    }
}
