package dev.yanianz.atrium.module.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RateLimitTest {
    @Test
    void backoffDoublesAndCaps() {
        assertEquals(1000, RateLimit.backoffMillis(0, 1000, 30_000));
        assertEquals(2000, RateLimit.backoffMillis(1, 1000, 30_000));
        assertEquals(4000, RateLimit.backoffMillis(2, 1000, 30_000));
        assertEquals(16_000, RateLimit.backoffMillis(4, 1000, 30_000));
        assertEquals(30_000, RateLimit.backoffMillis(5, 1000, 30_000));
        assertEquals(30_000, RateLimit.backoffMillis(60, 1000, 30_000));
        assertEquals(1, RateLimit.backoffMillis(-3, 0, 30_000));
    }

    @Test
    void parsesFractionalRetryAfterSeconds() {
        assertEquals(1500, RateLimit.retryAfterMillis("1.5", Instant.EPOCH, 999));
        assertEquals(2000, RateLimit.retryAfterMillis(" 2 ", Instant.EPOCH, 999));
        assertEquals(0, RateLimit.retryAfterMillis("0", Instant.EPOCH, 999));
    }

    @Test
    void parsesHttpDateRetryAfter() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        assertEquals(5000, RateLimit.retryAfterMillis("Thu, 01 Jan 2026 00:00:05 GMT", now, 999));
        assertEquals(0, RateLimit.retryAfterMillis("Wed, 31 Dec 2025 23:59:00 GMT", now, 999));
    }

    @Test
    void unreadableRetryAfterUsesTheFallback() {
        assertEquals(999, RateLimit.retryAfterMillis("soon", Instant.EPOCH, 999));
        assertEquals(999, RateLimit.retryAfterMillis(null, Instant.EPOCH, 999));
        assertEquals(999, RateLimit.retryAfterMillis("-5", Instant.EPOCH, 999));
        assertEquals(999, RateLimit.retryAfterMillis("NaN", Instant.EPOCH, 999));
    }

    @Test
    void parsesResetAfterAndRemaining() {
        assertEquals(250, RateLimit.resetAfterMillis("0.25"));
        assertEquals(-1, RateLimit.resetAfterMillis("nonsense"));
        assertEquals(-1, RateLimit.resetAfterMillis(null));
        assertTrue(RateLimit.remainingExhausted("0"));
        assertTrue(RateLimit.remainingExhausted(" 0 "));
        assertFalse(RateLimit.remainingExhausted("1"));
        assertFalse(RateLimit.remainingExhausted(null));
    }

    @Test
    void parsesAbsoluteResetIntoADelay() {
        assertEquals(2500, RateLimit.resetAtMillis("2.5", 0));
        assertEquals(0, RateLimit.resetAtMillis("1", 5000));
        assertEquals(-1, RateLimit.resetAtMillis("soon", 0));
        assertEquals(-1, RateLimit.resetAtMillis(null, 0));
    }

    @Test
    void nextAllowedAtSaturates() {
        assertEquals(1500, RateLimit.nextAllowedAt(1000, 500));
        assertEquals(1000, RateLimit.nextAllowedAt(1000, -10));
        assertEquals(Long.MAX_VALUE, RateLimit.nextAllowedAt(Long.MAX_VALUE - 5, 10));
    }
}
