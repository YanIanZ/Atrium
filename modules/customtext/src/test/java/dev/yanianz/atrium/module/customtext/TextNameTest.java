package dev.yanianz.atrium.module.customtext;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextNameTest {
    @Test void acceptsTheDocumentedAlphabet() {
        assertTrue(TextName.isValid("rules"));
        assertTrue(TextName.isValid("a"));
        assertTrue(TextName.isValid("motd-2"));
        assertTrue(TextName.isValid("my_text-1"));
        assertTrue(TextName.isValid("x".repeat(32)));
    }

    @Test void rejectsUppercaseAndSeparators() {
        assertFalse(TextName.isValid("Rules"));
        assertFalse(TextName.isValid("my text"));
        assertFalse(TextName.isValid("a.b"));
        assertFalse(TextName.isValid("a/b"));
        assertFalse(TextName.isValid("a%2"));
    }

    @Test void rejectsEmptyAndTooLongNames() {
        assertFalse(TextName.isValid(""));
        assertFalse(TextName.isValid(null));
        assertFalse(TextName.isValid("x".repeat(33)));
    }

    @Test void normalizesCommandInput() {
        assertEquals("rules", TextName.normalize(" Rules "));
        assertEquals("", TextName.normalize(null));
        assertEquals("motd", TextName.normalize("MOTD"));
    }
}
