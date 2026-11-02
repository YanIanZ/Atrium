package dev.yanianz.atrium.module.placeholders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UserMetaValuesTest {
    @Test
    void keysAreNormalizedAndRestricted() {
        assertEquals("rank_note", UserMetaValues.normalizeKey(" Rank_Note ").orElseThrow());
        assertTrue(UserMetaValues.normalizeKey("bad key").isEmpty());
        assertTrue(UserMetaValues.normalizeKey("a".repeat(33)).isEmpty());
        assertTrue(UserMetaValues.normalizeKey("").isEmpty());
        assertTrue(UserMetaValues.normalizeKey(null).isEmpty());
        assertTrue(UserMetaValues.normalizeKey("with/slash").isEmpty());
    }

    @Test
    void valuesRejectLineBreaksAndExcessLength() {
        assertEquals("hello world", UserMetaValues.normalizeValue(" hello world ").orElseThrow());
        assertTrue(UserMetaValues.normalizeValue("two\nlines").isEmpty());
        assertTrue(UserMetaValues.normalizeValue("tab\there").isEmpty());
        assertTrue(UserMetaValues.normalizeValue(" ").isEmpty());
        assertTrue(UserMetaValues.normalizeValue("x".repeat(513)).isEmpty());
    }

    @Test
    void placeholderKeyMustMatchAStoredKey() {
        assertTrue(UserMetaValues.handles("user_meta_rank_note"));
        assertFalse(UserMetaValues.handles("user_meta_"));
        assertEquals("rank_note", UserMetaValues.keyFromPlaceholder("user_meta_rank_note").orElseThrow());
        assertTrue(UserMetaValues.keyFromPlaceholder("user_meta_bad key").isEmpty());
    }

    @Test
    void incrementKeepsWholeNumbersWhole() {
        assertEquals("5", UserMetaValues.increment(null, "5").orElseThrow());
        assertEquals("5", UserMetaValues.increment("2", "3").orElseThrow());
        assertEquals("1.5", UserMetaValues.increment("1", "0.5").orElseThrow());
        assertEquals("-1", UserMetaValues.increment("2", "-3").orElseThrow());
        assertTrue(UserMetaValues.increment("many", "1").isEmpty());
        assertTrue(UserMetaValues.increment("1", "many").isEmpty());
    }

    @Test
    void incrementDefaultsToZeroAndOne() {
        assertEquals("1", UserMetaValues.increment("", "").orElseThrow());
        assertEquals("1", UserMetaValues.increment(null, null).orElseThrow());
    }
}
