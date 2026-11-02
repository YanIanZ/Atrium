package dev.yanianz.atrium.module.mail;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Pure formatting, validation and pagination helpers, unit tested without a server. */
final class MailFormat {
    private MailFormat() {}

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Message body without surrounding whitespace. */
    static String clean(String raw) {
        return raw == null ? "" : raw.strip();
    }

    static boolean blank(String body) {
        return body == null || body.isBlank();
    }

    static boolean tooLong(String body, int maxLength) {
        return body != null && body.length() > Math.max(1, maxLength);
    }

    static int pageCount(int total, int pageSize) {
        int size = Math.max(1, pageSize);
        return Math.max(1, (total + size - 1) / size);
    }

    /** Clamps a requested page into {@code 1..pages}. */
    static int page(int requested, int total, int pageSize) {
        return clamp(requested, 1, pageCount(total, pageSize));
    }

    /** Absolute epoch millis for an expiring mail; 0 means "never". */
    static long expiresAt(long now, Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return 0;
        }
        try {
            return Math.addExact(now, duration.toMillis());
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    static DateTimeFormatter formatter(String pattern, String zone) {
        String safePattern = pattern == null || pattern.isBlank() ? "yyyy-MM-dd HH:mm" : pattern;
        ZoneId id;
        try {
            id = zone == null || zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone.trim());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Invalid time zone '" + zone + "'", e);
        }
        return DateTimeFormatter.ofPattern(safePattern, Locale.ROOT).withZone(id);
    }

    static String date(DateTimeFormatter formatter, long epochMillis) {
        return formatter.format(Instant.ofEpochMilli(epochMillis));
    }
}
