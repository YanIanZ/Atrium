package dev.yanianz.atrium.module.worldrules;

import java.time.LocalTime;
import java.util.Locale;

/** Parsing for {@code /ptime}: named times, {@code HH:mm}, raw ticks and the action words. */
final class PlayerTimeSpec {
    enum Action {
        SET,
        FREEZE,
        UNFREEZE,
        RESET
    }

    /** {@code ticks} is {@code null} for {@link Action#FREEZE} without an explicit time. */
    record Spec(Action action, Long ticks) {}

    private PlayerTimeSpec() {}

    /** Returns {@code null} for an unknown word; numbers and clock times are accepted too. */
    static Spec parse(String raw, LocalTime now) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        switch (text) {
            case "reset":
            case "clear":
                return new Spec(Action.RESET, null);
            case "freeze":
            case "lock":
                return new Spec(Action.FREEZE, null);
            case "unfreeze":
            case "unlock":
            case "relative":
                return new Spec(Action.UNFREEZE, null);
            case "realtime":
            case "now":
                return new Spec(Action.SET, realTimeTicks(now == null ? LocalTime.MIDNIGHT : now));
            default:
                break;
        }
        Long ticks = parseTime(text);
        return ticks == null ? null : new Spec(Action.SET, ticks);
    }

    /** A named time, {@code HH:mm} or a raw tick count; {@code null} when the text is not a time. */
    static Long parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        switch (text) {
            case "morning":
            case "sunrise":
            case "dawn":
                return 23000L;
            case "day":
            case "noon-day":
                return 1000L;
            case "noon":
            case "midday":
                return 6000L;
            case "afternoon":
                return 9000L;
            case "dusk":
            case "sunset":
                return 12000L;
            case "night":
                return 13000L;
            case "midnight":
                return 18000L;
            default:
                break;
        }
        int colon = text.indexOf(':');
        if (colon > 0) {
            try {
                int hour = Integer.parseInt(text.substring(0, colon));
                int minute = Integer.parseInt(text.substring(colon + 1));
                if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                    return null;
                }
                return clockTicks(hour, minute);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            return Math.floorMod(Long.parseLong(text), 24000L);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Ticks for a real-world clock time: 06:00 is sunrise (0), one hour is 1000 ticks. */
    static long clockTicks(int hour, int minute) {
        long ticks = (hour - 6L) * 1000L + minute * 1000L / 60L;
        return Math.floorMod(ticks, 24000L);
    }

    static long realTimeTicks(LocalTime time) {
        return clockTicks(time.getHour(), time.getMinute());
    }

    /** Inverse of {@link #clockTicks}, formatted {@code HH:mm}. */
    static String describe(long ticks) {
        long t = Math.floorMod(ticks, 24000L);
        long minutesOfDay = (t * 60L / 1000L + 6L * 60L) % (24L * 60L);
        return String.format(Locale.ROOT, "%02d:%02d", minutesOfDay / 60L, minutesOfDay % 60L);
    }
}
