package dev.yanianz.atrium.module.teleport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class PositionTest {
    @Test
    void parsesAbsoluteAndRelative() {
        assertEquals(Optional.of(new RelativeCoordinate(false, 100)), RelativeCoordinate.parse("100"));
        assertEquals(Optional.of(new RelativeCoordinate(false, -3.5)), RelativeCoordinate.parse("-3.5"));
        assertEquals(Optional.of(new RelativeCoordinate(true, 0)), RelativeCoordinate.parse("~"));
        assertEquals(Optional.of(new RelativeCoordinate(true, 5)), RelativeCoordinate.parse("~5"));
        assertEquals(Optional.of(new RelativeCoordinate(true, -2.5)), RelativeCoordinate.parse("~-2.5"));
        assertEquals(Optional.of(new RelativeCoordinate(false, 0.5)), RelativeCoordinate.parse(".5"));
    }

    @Test
    void rejectsGarbage() {
        for (String bad : new String[] {"", " ", "abc", "~~", "~x", "1e5", "NaN", "Infinity", "0x10", "^1", "1,5", "--1", "~ 1", null}) {
            assertTrue(RelativeCoordinate.parse(bad).isEmpty(), "should reject: " + bad);
        }
        assertTrue(RelativeCoordinate.parse("99999999999").isEmpty());
    }

    @Test
    void resolvesAgainstBase() {
        Position position = Position.parse("~ ~10 -250").orElseThrow();
        assertArrayEquals(new double[] {12.3, 74, -249.5}, position.resolve(12.3, 64, 7), 1e-9);
    }

    @Test
    void centresWholeAbsoluteHorizontalNumbersOnly() {
        Position position = Position.parse("10 64 10.25").orElseThrow();
        assertArrayEquals(new double[] {10.5, 64, 10.25}, position.resolve(0, 0, 0), 1e-9);
        Position relative = Position.parse("~1 ~ ~1").orElseThrow();
        assertArrayEquals(new double[] {1.5, 70, -0.5}, relative.resolve(0.5, 70, -1.5), 1e-9);
    }

    @Test
    void needsExactlyThreeParts() {
        assertTrue(Position.parse("1 2").isEmpty());
        assertTrue(Position.parse("1 2 3 4").isEmpty());
        assertTrue(Position.parse("1 x 3").isEmpty());
        assertTrue(Position.parse("  1   2   3 ").isPresent());
    }

    @Test
    void horizontalLimit() {
        assertTrue(RelativeCoordinate.withinHorizontalLimit(29_999_999));
        assertFalse(RelativeCoordinate.withinHorizontalLimit(-30_000_001));
    }
}
