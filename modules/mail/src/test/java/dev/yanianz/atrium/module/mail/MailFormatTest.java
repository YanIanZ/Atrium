package dev.yanianz.atrium.module.mail;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MailFormatTest {
    @Test void clampsBounds() {
        assertEquals(1, MailFormat.clamp(0, 1, 10));
        assertEquals(10, MailFormat.clamp(11, 1, 10));
        assertEquals(5, MailFormat.clamp(5, 1, 10));
    }

    @Test void trimsBodiesAndRejectsBlankOrOverlongText() {
        assertEquals("hello", MailFormat.clean("  hello \n"));
        assertTrue(MailFormat.blank("   "));
        assertFalse(MailFormat.blank("x"));
        assertFalse(MailFormat.tooLong("abc", 3));
        assertTrue(MailFormat.tooLong("abcd", 3));
    }

    @Test void paginatesAndClampsPageNumbers() {
        assertEquals(1, MailFormat.pageCount(0, 5));
        assertEquals(1, MailFormat.pageCount(5, 5));
        assertEquals(2, MailFormat.pageCount(6, 5));
        assertEquals(1, MailFormat.page(0, 12, 5));
        assertEquals(3, MailFormat.page(9, 12, 5));
    }

    @Test void computesExpirySafely() {
        long now = 1_000L;
        assertEquals(0, MailFormat.expiresAt(now, Duration.ZERO));
        assertEquals(0, MailFormat.expiresAt(now, Duration.ofSeconds(-1)));
        assertEquals(2_000L, MailFormat.expiresAt(now, Duration.ofSeconds(1)));
        assertEquals(Long.MAX_VALUE, MailFormat.expiresAt(Long.MAX_VALUE - 1, Duration.ofSeconds(10)));
    }

    @Test void formatsDatesInTheConfiguredZone() {
        DateTimeFormatter formatter = MailFormat.formatter("yyyy-MM-dd HH:mm", "UTC");
        assertEquals("1970-01-01 00:00", MailFormat.date(formatter, 0));
        assertThrows(IllegalArgumentException.class, () -> MailFormat.formatter("yyyy", "Not/AZone"));
    }
}
