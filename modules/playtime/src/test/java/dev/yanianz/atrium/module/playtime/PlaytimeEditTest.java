package dev.yanianz.atrium.module.playtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Duration arithmetic behind /editplaytime. */
class PlaytimeEditTest {
    @Test
    void setReplacesTheTotalExactly() {
        assertEquals(3_600, PlaytimeEdit.nextTotal(99_999, PlaytimeEdit.Mode.SET, 3_600));
        assertEquals(0, PlaytimeEdit.nextTotal(99_999, PlaytimeEdit.Mode.SET, 0));
    }

    @Test
    void addStacksAndSaturatesInsteadOfOverflowing() {
        assertEquals(4_600, PlaytimeEdit.nextTotal(1_000, PlaytimeEdit.Mode.ADD, 3_600));
        assertEquals(Long.MAX_VALUE, PlaytimeEdit.nextTotal(Long.MAX_VALUE - 5, PlaytimeEdit.Mode.ADD, 10));
    }

    @Test
    void takeNeverGoesBelowZero() {
        assertEquals(400, PlaytimeEdit.nextTotal(1_000, PlaytimeEdit.Mode.TAKE, 600));
        assertEquals(0, PlaytimeEdit.nextTotal(1_000, PlaytimeEdit.Mode.TAKE, 1_000));
        assertEquals(0, PlaytimeEdit.nextTotal(1_000, PlaytimeEdit.Mode.TAKE, 999_999));
        assertEquals(0, PlaytimeEdit.nextTotal(0, PlaytimeEdit.Mode.TAKE, 60));
    }

    @Test
    void parsesModesAndRejectsNegativeAmounts() {
        assertEquals(PlaytimeEdit.Mode.SET, PlaytimeEdit.Mode.parse("SET").orElseThrow());
        assertEquals(PlaytimeEdit.Mode.ADD, PlaytimeEdit.Mode.parse("add").orElseThrow());
        assertEquals(PlaytimeEdit.Mode.TAKE, PlaytimeEdit.Mode.parse("take").orElseThrow());
        assertEquals(PlaytimeEdit.Mode.TAKE, PlaytimeEdit.Mode.parse("remove").orElseThrow());
        assertEquals("take", PlaytimeEdit.Mode.TAKE.key());
        assertThrows(IllegalArgumentException.class,
                () -> PlaytimeEdit.nextTotal(10, PlaytimeEdit.Mode.ADD, -1));
    }

    @Test
    void requestDurationStillRejectsNegativeValues() {
        assertEquals(90, PlaytimeModule.requireSeconds(Duration.ofSeconds(90)));
        assertThrows(IllegalArgumentException.class,
                () -> PlaytimeModule.requireSeconds(Duration.ofSeconds(-1)));
    }
}
