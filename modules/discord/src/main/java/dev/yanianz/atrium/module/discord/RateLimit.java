package dev.yanianz.atrium.module.discord;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.jspecify.annotations.Nullable;

/** Pure rate-limit and backoff arithmetic, kept separate so it can be tested without a server. */
public final class RateLimit {
    private RateLimit() {}

    /** Exponential backoff: {@code base * 2^attempt}, capped at {@code max}. Attempt 0 is the first retry. */
    public static long backoffMillis(int attempt, long baseMillis, long maxMillis) {
        long base = Math.max(1L, baseMillis);
        long max = Math.max(base, maxMillis);
        int shift = Math.min(Math.max(0, attempt), 20);
        long delay;
        try {
            delay = Math.multiplyExact(base, 1L << shift);
        } catch (ArithmeticException e) {
            return max;
        }
        return Math.min(max, delay);
    }

    /**
     * Parses a {@code Retry-After} header (seconds, possibly fractional, or an RFC 1123 date). Returns
     * {@code fallbackMillis} when the value is missing or unreadable.
     */
    public static long retryAfterMillis(@Nullable String header, Instant now, long fallbackMillis) {
        if (header == null || header.isBlank()) {
            return fallbackMillis;
        }
        String value = header.trim();
        try {
            double seconds = Double.parseDouble(value);
            if (Double.isFinite(seconds) && seconds >= 0) {
                return secondsToMillis(seconds);
            }
        } catch (NumberFormatException ignored) {
            // not a number; try an HTTP date below
        }
        try {
            Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return Math.max(0L, Duration.between(now, at).toMillis());
        } catch (DateTimeParseException ignored) {
            return fallbackMillis;
        }
    }

    /** Parses {@code X-RateLimit-Reset-After} (fractional seconds). Returns -1 when absent or unreadable. */
    public static long resetAfterMillis(@Nullable String header) {
        if (header == null || header.isBlank()) {
            return -1L;
        }
        try {
            double seconds = Double.parseDouble(header.trim());
            if (Double.isFinite(seconds) && seconds >= 0) {
                return secondsToMillis(seconds);
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return -1L;
    }

    /**
     * Parses {@code X-RateLimit-Reset} (absolute epoch seconds) into a delay from {@code nowMillis}.
     * Returns -1 when absent or unreadable.
     */
    public static long resetAtMillis(@Nullable String header, long nowMillis) {
        if (header == null || header.isBlank()) {
            return -1L;
        }
        try {
            double seconds = Double.parseDouble(header.trim());
            if (Double.isFinite(seconds) && seconds >= 0) {
                long at = secondsToMillis(seconds);
                return at <= nowMillis ? 0L : at - nowMillis;
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return -1L;
    }

    /** {@code true} when the bucket has no requests left ({@code X-RateLimit-Remaining: 0}). */
    public static boolean remainingExhausted(@Nullable String header) {
        return header != null && "0".equals(header.trim());
    }

    /** Saturating {@code now + delay}; never overflows into the past. */
    public static long nextAllowedAt(long now, long delayMillis) {
        if (delayMillis <= 0) {
            return now;
        }
        if (now > Long.MAX_VALUE - delayMillis) {
            return Long.MAX_VALUE;
        }
        return now + delayMillis;
    }

    private static long secondsToMillis(double seconds) {
        double millis = Math.ceil(seconds * 1000.0);
        if (millis >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return (long) millis;
    }
}
