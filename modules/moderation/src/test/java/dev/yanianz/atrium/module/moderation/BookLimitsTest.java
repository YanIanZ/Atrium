package dev.yanianz.atrium.module.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class BookLimitsTest {
    @Test
    void theConfiguredDefaultAppliesWithoutAPermission() {
        assertEquals(20, BookLimits.resolve(OptionalInt.empty(), false, 20));
        assertEquals(Integer.MAX_VALUE, BookLimits.resolve(OptionalInt.empty(), false, 0),
                "a disabled default never limits");
    }

    @Test
    void aNumericNodeCanRaiseAndLowerTheLimit() {
        assertEquals(40, BookLimits.resolve(OptionalInt.of(40), false, 20));
        assertEquals(5, BookLimits.resolve(OptionalInt.of(5), false, 20));
        assertEquals(0, BookLimits.resolve(OptionalInt.of(-3), false, 20), "negative nodes never mean unlimited");
    }

    @Test
    void theUnlimitedNodeWins() {
        assertEquals(Integer.MAX_VALUE, BookLimits.resolve(OptionalInt.of(3), true, 20));
    }

    @Test
    void pagesBeyondTheLimitAreCut() {
        assertTrue(BookLimits.exceeded(21, 20));
        assertFalse(BookLimits.exceeded(20, 20));
        assertFalse(BookLimits.exceeded(1000, Integer.MAX_VALUE));
        assertEquals(20, BookLimits.keep(21, 20));
        assertEquals(12, BookLimits.keep(12, 20));
        assertEquals(0, BookLimits.keep(0, 20));
        assertEquals(0, BookLimits.keep(5, 0));
    }
}
