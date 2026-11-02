package dev.yanianz.atrium.module.ranks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VoteMathTest {
    @Test
    void namesAreValidatedAndLowerCased() {
        assertEquals("notch", VoteMath.normalizeName(" Notch ").orElseThrow());
        assertTrue(VoteMath.validName("a_1"));
        assertFalse(VoteMath.validName("a-b"));
        assertFalse(VoteMath.validName("a".repeat(17)));
        assertTrue(VoteMath.normalizeName(null).isEmpty());
        assertTrue(VoteMath.normalizeName("bad name").isEmpty());
    }

    @Test
    void setAddAndTakeNeverGoBelowZero() {
        assertEquals(0L, VoteMath.set(-4L));
        assertEquals(7L, VoteMath.set(7L));
        assertEquals(5L, VoteMath.add(2L, 3L));
        assertEquals(Long.MAX_VALUE, VoteMath.add(Long.MAX_VALUE, 1L));
        assertEquals(0L, VoteMath.take(2L, 3L));
        assertEquals(2L, VoteMath.take(5L, 3L));
        assertEquals(2L, VoteMath.take(2L, -10L));
    }

    @Test
    void pagesAndOffsetsAreClamped() {
        assertEquals(1, VoteMath.pages(0, 10));
        assertEquals(2, VoteMath.pages(11, 10));
        assertEquals(3, VoteMath.pages(30, 10));
        assertEquals(1, VoteMath.clampPage(0, 3));
        assertEquals(3, VoteMath.clampPage(9, 3));
        assertEquals(0, VoteMath.offset(1, 10));
        assertEquals(20, VoteMath.offset(3, 10));
        assertEquals(0, VoteMath.offset(0, 10));
    }
}
