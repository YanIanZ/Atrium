package dev.yanianz.atrium.module.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MotdTextTest {
    @Test
    void parsesLineBreaksAndPipes() {
        assertEquals(List.of("one", "two"), MotdText.parse("one\\ntwo"));
        assertEquals(List.of("one", "two"), MotdText.parse("one|two"));
        assertEquals(List.of("one two"), MotdText.parse("  one two  "));
    }

    @Test
    void keepsAtMostTwoLinesAndDropsEmptyOnes() {
        assertEquals(List.of("a", "b"), MotdText.parse("a||b|c"));
        assertEquals(List.of(), MotdText.parse("   "));
        assertEquals(List.of(), MotdText.normalize(null));
    }

    @Test
    void cutsVeryLongLines() {
        String longLine = "x".repeat(MotdText.MAX_LINE_LENGTH + 50);
        String stored = MotdText.normalize(List.of(longLine)).getFirst();
        assertEquals(MotdText.MAX_LINE_LENGTH, stored.length());
    }

    @Test
    void replacesTheNumberPlaceholders() {
        assertEquals("12/30 online", MotdText.withNumbers("<online>/<max> online", 12, 30));
        assertFalse(MotdText.withNumbers("plain", 1, 2).contains("<"));
        assertTrue(MotdText.withNumbers("max <max>", 1, 7).endsWith("7"));
    }
}
