package dev.yanianz.atrium.module.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EditorInputTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void idsMustBePositive() {
        assertTrue(EditorInput.validId(1));
        assertTrue(EditorInput.validId(Long.MAX_VALUE));
        assertFalse(EditorInput.validId(0), "-id:0 must never reach remove(-1)");
        assertFalse(EditorInput.validId(-1));
        assertFalse(EditorInput.validId(Long.MIN_VALUE));
    }

    @Test
    void reasonIsTrimmedAndCapped() {
        assertEquals(Optional.of("spamming chat"), EditorInput.reason("  spamming chat  ", 50));
        assertTrue(EditorInput.reason("   ", 50).isEmpty());
        assertTrue(EditorInput.reason(null, 50).isEmpty());
        assertEquals(Optional.of("abc"), EditorInput.reason("abcdef", 3));
        assertEquals(Optional.of("a"), EditorInput.reason("abcdef", 0), "a broken limit still keeps one character");
    }

    @Test
    void expiryAcceptsDurationsAndPermanentAliases() {
        EditorInput.Expiry inHour = EditorInput.expiry("1h", NOW).orElseThrow();
        assertFalse(inHour.permanent());
        assertEquals(NOW.plus(Duration.ofHours(1)), inHour.expires());

        for (String alias : new String[] {"permanent", "NEVER", "none", "off", "0", " permanent "}) {
            EditorInput.Expiry parsed = EditorInput.expiry(alias, NOW).orElseThrow();
            assertTrue(parsed.permanent(), alias);
        }
    }

    @Test
    void expiryRejectsZeroNegativeAndGarbage() {
        assertTrue(EditorInput.expiry("0s", NOW).isEmpty());
        assertTrue(EditorInput.expiry("-5m", NOW).isEmpty());
        assertTrue(EditorInput.expiry("tomorrow", NOW).isEmpty());
        assertTrue(EditorInput.expiry("   ", NOW).isEmpty());
        assertTrue(EditorInput.expiry(null, NOW).isEmpty());
    }

    @Test
    void allowedCommandsAreNormalisedAndValidated() {
        assertEquals(Optional.of("msg"), EditorInput.allowedCommand(" MSG "));
        assertEquals(Optional.of("mail send"), EditorInput.allowedCommand("/mail   send"));
        assertEquals(Optional.of("minecraft:msg"), EditorInput.allowedCommand("minecraft:msg"));
        assertTrue(EditorInput.allowedCommand("*").isEmpty(), "a wildcard would allow everything");
        assertTrue(EditorInput.allowedCommand("mail send extra words beyond the allowed limit here").isEmpty());
        assertTrue(EditorInput.allowedCommand("say <click>").isEmpty());
        assertTrue(EditorInput.allowedCommand("").isEmpty());
        assertTrue(EditorInput.allowedCommand(null).isEmpty());
    }

    @Test
    void booleansAcceptCommonAliases() {
        assertEquals(Optional.of(true), EditorInput.bool("TRUE"));
        assertEquals(Optional.of(true), EditorInput.bool("on"));
        assertEquals(Optional.of(false), EditorInput.bool(" off "));
        assertEquals(Optional.of(false), EditorInput.bool("0"));
        assertTrue(EditorInput.bool("maybe").isEmpty());
    }

    @Test
    void maxSentenceParsesLikeAnExpiry() {
        assertTrue(EditorInput.maxSentence("none").orElseThrow().unlimited());
        assertEquals(Duration.ofDays(7), EditorInput.maxSentence("7d").orElseThrow().value());
        assertTrue(EditorInput.maxSentence("0s").isEmpty());
        assertTrue(EditorInput.maxSentence("nope").isEmpty());
    }

    @Test
    void sentenceIsCappedToTheJailMaximum() {
        Duration max = Duration.ofHours(2);
        assertEquals(max, EditorInput.cap(null, max), "permanent requests become the maximum");
        assertEquals(max, EditorInput.cap(Duration.ofDays(1), max));
        assertEquals(Duration.ofMinutes(30), EditorInput.cap(Duration.ofMinutes(30), max));
        assertEquals(Duration.ofDays(1), EditorInput.cap(Duration.ofDays(1), null), "no maximum leaves the request");
        org.junit.jupiter.api.Assertions.assertNull(EditorInput.cap(null, null));
    }
}
