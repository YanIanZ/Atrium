package dev.yanianz.atrium.module.performance;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class GroundCleanCountdownTest {
    private static final List<Integer> MARKS = List.of(60, 30, 10, 5);

    @Test void sixtySecondsAnnouncesEveryMarkLongestFirst() {
        assertEquals(List.of(60, 30, 10, 5), GroundCleanCountdown.warnings(60, MARKS));
        assertEquals(List.of(60, 30, 10, 5),
                GroundCleanCountdown.warnings(60, List.of(5, 10, 30, 60, 10)));
    }

    @Test void onlyMarksInsideTheCountdownAreAnnounced() {
        assertEquals(List.of(10, 5), GroundCleanCountdown.warnings(10, MARKS));
        assertEquals(List.of(5), GroundCleanCountdown.warnings(7, MARKS));
        assertEquals(List.of(), GroundCleanCountdown.warnings(0, MARKS));
        assertEquals(List.of(), GroundCleanCountdown.warnings(-5, MARKS));
    }

    @Test void invalidMarksAreIgnored() {
        assertEquals(List.of(30, 10), GroundCleanCountdown.warnings(60, java.util.Arrays.asList(null, 0, -1, 30, 10)));
        assertEquals(List.of(), GroundCleanCountdown.warnings(60, null));
    }
}
