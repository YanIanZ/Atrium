package dev.yanianz.atrium.module.chatbubbles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class BubbleTextTest {
    @Test
    void wrapsAtWordBoundaries() {
        assertEquals(List.of("hello", "world"), BubbleText.lines("hello world", 100, 5, 3));
        assertEquals(List.of("one two", "three"), BubbleText.lines("one two three", 100, 8, 4));
    }

    @Test
    void hardSplitsWordsLongerThanTheLine() {
        assertEquals(List.of("abcd", "efgh"), BubbleText.lines("abcdefgh", 100, 4, 3));
        assertEquals(List.of("a", "b", "c"), BubbleText.lines("abc", 100, 1, 5));
    }

    @Test
    void truncatesTheWholeMessageAndMarksIt() {
        assertEquals(List.of("he\u2026"), BubbleText.lines("hello", 3, 10, 2));
    }

    @Test
    void capsTheNumberOfLinesAndMarksTheLastOne() {
        assertEquals(List.of("one", "two\u2026"), BubbleText.lines("one two three", 100, 5, 2));
        assertEquals(List.of("one\u2026"), BubbleText.lines("one two three", 100, 5, 1));
    }

    @Test
    void normalisesWhitespaceIncludingNewlinesAndTabs() {
        assertEquals(List.of("a b c"), BubbleText.lines("a\n b\tc", 100, 20, 2));
        assertTrue(BubbleText.lines("   ", 100, 20, 2).isEmpty());
        assertTrue(BubbleText.lines(null, 100, 20, 2).isEmpty());
    }

    @Test
    void everyLineStaysWithinTheConfiguredWidth() {
        for (String line : BubbleText.lines("the quick brown fox jumps over the lazy dog", 80, 7, 4)) {
            assertTrue(line.length() <= 7, line);
        }
    }
}
