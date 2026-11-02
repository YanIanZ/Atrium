package dev.yanianz.atrium.module.itemtools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SavedItemNameTest {
    @Test
    void acceptsSimpleLowercaseNames() {
        assertTrue(SavedItemName.isValid("sword"));
        assertTrue(SavedItemName.isValid("diamond_sword-2"));
        assertTrue(SavedItemName.isValid("a"));
        assertTrue(SavedItemName.isValid("9"));
    }

    @Test
    void rejectsNamesThatCouldBreakACommandOrAPath() {
        assertFalse(SavedItemName.isValid(""));
        assertFalse(SavedItemName.isValid(null));
        assertFalse(SavedItemName.isValid("Sword"));
        assertFalse(SavedItemName.isValid("two words"));
        assertFalse(SavedItemName.isValid("../etc"));
        assertFalse(SavedItemName.isValid("a".repeat(SavedItemName.MAX_LENGTH + 1)));
    }

    @Test
    void normalisesWhitespaceAndCase() {
        assertEquals("diamond_sword", SavedItemName.normalize("  Diamond_Sword  "));
        assertThrows(IllegalArgumentException.class, () -> SavedItemName.normalize("bad name"));
        assertThrows(IllegalArgumentException.class, () -> SavedItemName.normalize(null));
    }
}
