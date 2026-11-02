package dev.yanianz.atrium.module.playtime;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Optional;

/** Period selectors for /playtimetop, with locale-independent calendar boundaries. */
enum PlaytimePeriod {
    DAY,
    WEEK,
    MONTH,
    ALL;

    static Optional<PlaytimePeriod> parse(String input) {
        if (input == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(input.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** First day included in the period, or {@code null} for all time. Week starts on Monday. */
    LocalDate start(LocalDate today) {
        return switch (this) {
            case DAY -> today;
            case WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> today.withDayOfMonth(1);
            case ALL -> null;
        };
    }

    String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
