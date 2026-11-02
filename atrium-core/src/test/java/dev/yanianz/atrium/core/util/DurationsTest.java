package dev.yanianz.atrium.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DurationsTest {
    @Test
    void parsesCompoundUnits() {
        assertEquals(Duration.ofDays(1).plusHours(2).plusMinutes(30), Durations.parse("1d2h30m").orElseThrow());
        assertEquals(Duration.ofDays(14), Durations.parse("2w").orElseThrow());
        assertEquals(Duration.ofSeconds(45), Durations.parse("45").orElseThrow());
        assertEquals(Duration.ofMillis(250), Durations.parse("250ms").orElseThrow());
        assertEquals(Duration.ofDays(30), Durations.parse("1mo").orElseThrow());
    }

    @Test
    void rejectsGarbageAndOverflow() {
        assertTrue(Durations.parse("abc").isEmpty());
        assertTrue(Durations.parse("1x").isEmpty());
        assertTrue(Durations.parse("-5m").isEmpty());
        assertTrue(Durations.parse("").isEmpty());
        assertTrue(Durations.parse("99999999999999999999d").isEmpty());
    }

    @Test
    void formatsCompactly() {
        assertEquals("1d 2h 5m", Durations.format(Duration.ofSeconds(93_900), 3));
        assertEquals("1d 2h", Durations.format(Duration.ofSeconds(93_900), 2));
        assertEquals("0s", Durations.format(Duration.ZERO, 3));
        assertEquals("59s", Durations.format(Duration.ofSeconds(59), 3));
    }
}
