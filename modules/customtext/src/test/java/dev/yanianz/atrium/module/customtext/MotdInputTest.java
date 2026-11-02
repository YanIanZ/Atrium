package dev.yanianz.atrium.module.customtext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MotdInputTest {
    @Test
    void splitsOnTheEscapedNewlineAndOnAPipe() {
        assertEquals(List.of("a", "b"), MotdInput.parse("a\\nb"));
        assertEquals(List.of("a", "b"), MotdInput.parse("a|b"));
    }

    @Test
    void keepsAtMostTwoNonEmptyLines() {
        assertEquals(List.of("a", "b"), MotdInput.parse("a||b|c"));
        assertTrue(MotdInput.parse("   ").isEmpty());
        assertTrue(MotdInput.parse(null).isEmpty());
    }

    @Test
    void trimsAndTruncates() {
        assertEquals(List.of("hello world"), MotdInput.parse("  hello world  "));
        String longLine = "y".repeat(MotdInput.MAX_LINE_LENGTH + 10);
        assertEquals(MotdInput.MAX_LINE_LENGTH, MotdInput.parse(longLine).getFirst().length());
    }
}
