package dev.yanianz.atrium.module.npcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NpcScaleTest {
    @Test void acceptsValuesInsideTheRange() {
        assertEquals(0.1, NpcScale.parse("0.1").orElseThrow(), 1e-9);
        assertEquals(1.5, NpcScale.parse(" 1.5 ").orElseThrow(), 1e-9);
        assertEquals(5.0, NpcScale.parse("5").orElseThrow(), 1e-9);
        assertEquals(2.0, NpcScale.parse("2.00").orElseThrow(), 1e-9);
    }

    @Test void rejectsValuesOutsideTheRange() {
        assertTrue(NpcScale.parse("0.09").isEmpty());
        assertTrue(NpcScale.parse("5.01").isEmpty());
        assertTrue(NpcScale.parse("0").isEmpty());
        assertTrue(NpcScale.parse("-1").isEmpty());
    }

    @Test void rejectsNonNumbers() {
        assertTrue(NpcScale.parse("abc").isEmpty());
        assertTrue(NpcScale.parse("").isEmpty());
        assertTrue(NpcScale.parse(null).isEmpty());
        assertTrue(NpcScale.parse("1,5").isEmpty());
        assertTrue(NpcScale.parse("NaN").isEmpty());
        assertTrue(NpcScale.parse("Infinity").isEmpty());
    }

    @Test void boundsAreInclusive() {
        assertTrue(NpcScale.valid(NpcScale.MIN));
        assertTrue(NpcScale.valid(NpcScale.MAX));
        assertFalse(NpcScale.valid(Double.NaN));
        assertFalse(NpcScale.valid(Double.POSITIVE_INFINITY));
    }
}
