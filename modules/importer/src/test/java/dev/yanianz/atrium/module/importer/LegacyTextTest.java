package dev.yanianz.atrium.module.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LegacyTextTest {
    @Test
    void convertsColourAndDecorationCodes() {
        assertEquals("<green>Hello <bold>World", LegacyText.toMiniMessage("&aHello &lWorld"));
    }

    @Test
    void convertsSectionSignAndReset() {
        assertEquals("<gold>Gold<reset> plain", LegacyText.toMiniMessage("\u00a76Gold\u00a7r plain"));
    }

    @Test
    void convertsHexAndExpandedHex() {
        assertEquals("<#FF8800>Hex", LegacyText.toMiniMessage("&#FF8800Hex"));
        assertEquals("<#FF8800>Expanded", LegacyText.toMiniMessage("&x&f&f&8&8&0&0Expanded"));
    }

    @Test
    void escapesExistingMiniMessageTags() {
        String converted = LegacyText.toMiniMessage("<red>&aX");
        assertEquals("<red>X", LegacyText.plain(converted));
        // The literal tag must not colour the text before it.
        assertTrue(converted.startsWith("\\<red"));
    }

    @Test
    void plainTextHasNoTags() {
        assertEquals("Hello World", LegacyText.plain(LegacyText.toMiniMessage("&aHello &lWorld")));
    }

    @Test
    void emptyInput() {
        assertEquals("", LegacyText.toMiniMessage(null));
        assertEquals("", LegacyText.toMiniMessage(""));
        assertFalse(LegacyText.toMiniMessage("&z unknown").contains("<"));
    }
}
