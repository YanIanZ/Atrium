package dev.yanianz.atrium.module.signs;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure window maths for dynamic scrolling signs. */
class SignScrollTest {
    @Test void shortListsDoNotScrollAndAreBlankPadded() {
        List<String> lines = List.of("one", "two");
        assertFalse(SignScroll.scrolls(lines));
        assertEquals(0, SignScroll.step(lines));
        assertEquals(List.of("one", "two", "", ""), SignScroll.window(lines, 0));
        assertEquals(List.of("one", "two", "", ""), SignScroll.window(lines, 99));
    }

    @Test void longListsWrapAroundThroughFourLines() {
        List<String> lines = List.of("1", "2", "3", "4", "5", "6");
        assertTrue(SignScroll.scrolls(lines));
        assertEquals(List.of("1", "2", "3", "4"), SignScroll.window(lines, 0));
        assertEquals(List.of("2", "3", "4", "5"), SignScroll.window(lines, 1));
        assertEquals(List.of("6", "1", "2", "3"), SignScroll.window(lines, 5));
        assertEquals(List.of("1", "2", "3", "4"), SignScroll.window(lines, 6));
        assertEquals(List.of("1", "2", "3", "4"), SignScroll.window(lines, -6));
    }

    @Test void exactFourOrFewerNeverWrap() {
        List<String> lines = List.of("1", "2", "3", "4");
        assertFalse(SignScroll.scrolls(lines));
        assertEquals(List.of("1", "2", "3", "4"), SignScroll.window(lines, 3));
    }

    @Test void emptyListIsAllBlanks() {
        assertEquals(List.of("", "", "", ""), SignScroll.window(List.of(), 0));
        assertEquals(List.of("", "", "", ""), SignScroll.window(null, 4));
    }

    @Test void underscoreMeansBlankAndControlCharactersAreDropped() {
        assertEquals(List.of("a", "", "b c"), SignScroll.sanitize(List.of(" a ", "_", "b\nc")));
        assertEquals(List.of(), SignScroll.sanitize(null));
        assertEquals(512, SignScroll.sanitize(java.util.Collections.nCopies(600, "x")).size());
    }

    @Test void framesAdvanceWithTime() {
        assertEquals(0, SignScroll.frame(0, 1000, 0));
        assertEquals(1, SignScroll.frame(1000, 1000, 0));
        assertEquals(1, SignScroll.frame(1999, 1000, 0));
        assertEquals(2, SignScroll.frame(1000, 1000, 1));
        // A too-small interval is clamped rather than dividing by zero.
        assertEquals(20, SignScroll.frame(1000, 0, 0));
    }

    @Test void personalOffsetsAreStableAndDifferent() {
        UUID first = UUID.randomUUID();
        assertEquals(SignScroll.offsetFor(first), SignScroll.offsetFor(first));
        assertTrue(SignScroll.offsetFor(first) >= 0);
        assertTrue(SignScroll.offsetFor(first) < 97);
    }

    @Test void linesSplitOnPipeAndJoinBack() {
        assertEquals(List.of("a", "", "b"), SignScroll.split("a||b"));
        assertEquals(List.of("a b"), SignScroll.split("  a b  "));
        assertEquals(List.of(), SignScroll.split(""));
        assertEquals(List.of(), SignScroll.split(null));
        assertEquals("a | _ | b", SignScroll.join(List.of("a", "", "b")));
    }

    @Test void namesAreNormalisedForLookup() {
        assertEquals("shop", SignScroll.key("SHOP"));
    }
}
