package dev.yanianz.atrium.module.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JailClockTest {
    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    @Test
    void anAbsenceIsAddedBackInFull() {
        assertEquals(Duration.ofHours(3), JailClock.offlineExtension(NOW.minus(Duration.ofHours(3)), NOW));
    }

    @Test
    void aBackwardsClockNeverShortensASentence() {
        assertEquals(Duration.ZERO, JailClock.offlineExtension(NOW.plusSeconds(60), NOW));
        assertEquals(Duration.ZERO, JailClock.offlineExtension(null, NOW));
        assertEquals(Duration.ZERO, JailClock.offlineExtension(NOW, NOW));
    }

    @Test
    void aHugeAbsenceIsCapped() {
        assertEquals(JailClock.MAX_EXTENSION,
                JailClock.offlineExtension(NOW.minus(Duration.ofDays(900)), NOW));
    }

    @Test
    void afkTimeComesFromTheMonotonicClock() {
        assertEquals(Duration.ofSeconds(30), JailClock.afkExtension(Duration.ofSeconds(30).toNanos()));
        assertEquals(Duration.ZERO, JailClock.afkExtension(0));
        assertEquals(Duration.ZERO, JailClock.afkExtension(-5));
        assertEquals(JailClock.MAX_EXTENSION, JailClock.afkExtension(Duration.ofDays(400).toNanos()));
    }
}
