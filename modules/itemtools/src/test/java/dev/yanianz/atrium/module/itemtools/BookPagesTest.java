package dev.yanianz.atrium.module.itemtools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BookPagesTest {
    @Test
    void emptyAndBlankTextHasNoPages() {
        assertTrue(BookPages.split(null).isEmpty());
        assertTrue(BookPages.split("").isEmpty());
        assertTrue(BookPages.split("   \n \n").isEmpty());
    }

    @Test
    void onePageKeepsLineOrder() {
        List<List<String>> pages = BookPages.split("first\nsecond\nthird");
        assertEquals(List.of(List.of("first", "second", "third")), pages);
    }

    @Test
    void separatorStartsANewPageAndBlankPagesAreDropped() {
        List<List<String>> pages = BookPages.split("one\n---\ntwo\nthree\n---\n---\nfour");
        assertEquals(List.of(List.of("one"), List.of("two", "three"), List.of("four")), pages);
    }

    @Test
    void carriageReturnsAreNormalised() {
        assertEquals(List.of(List.of("a", "b")), BookPages.split("a\r\nb\r"));
    }

    @Test
    void longPagesAreSplitInsteadOfDropped() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < BookPages.MAX_LINES * 2 + 1; i++) {
            text.append("line ").append(i).append('\n');
        }
        List<List<String>> pages = BookPages.split(text.toString());
        assertEquals(3, pages.size());
        assertEquals(BookPages.MAX_LINES, pages.get(0).size());
        assertEquals(BookPages.MAX_LINES, pages.get(1).size());
        assertEquals(1, pages.get(2).size());
        assertEquals("line 28", pages.get(2).getFirst());
    }

    @Test
    void longLinesAreCutAtTheProtocolLimit() {
        String line = "x".repeat(BookPages.MAX_LINE_LENGTH + 40);
        List<String> page = BookPages.split(line).getFirst();
        assertEquals(BookPages.MAX_LINE_LENGTH, page.getFirst().length());
    }

    @Test
    void trailingEmptyLinesAreTrimmed() {
        assertEquals(List.of(List.of("a")), BookPages.split("a\n\n\n"));
    }

    @Test
    void pageCountIsCapped() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < BookPages.MAX_PAGES + 50; i++) {
            text.append("page ").append(i).append("\n---\n");
        }
        List<List<String>> pages = BookPages.split(text.toString());
        assertEquals(BookPages.MAX_PAGES, pages.size());
        assertEquals("page 0", pages.getFirst().getFirst());
    }

    @Test
    void pagesAreImmutable() {
        List<List<String>> pages = BookPages.split("a\n---\nb");
        List<List<String>> copy = new ArrayList<>(pages);
        assertEquals(2, copy.size());
        for (List<String> page : pages) {
            assertEquals(List.copyOf(page), page);
        }
    }

    @Test
    void normalizeAppliesTheSameLimitsToForeignPages() {
        List<List<String>> foreign = List.of(
                List.of("  padded  ", "x".repeat(400)),
                List.of("", "   "),
                List.of("kept"));
        List<List<String>> pages = BookPages.normalize(foreign);
        assertEquals(2, pages.size());
        assertEquals("padded", pages.get(0).getFirst());
        assertEquals(BookPages.MAX_LINE_LENGTH, pages.get(0).get(1).length());
        assertEquals(List.of("kept"), pages.get(1));
        assertTrue(BookPages.normalize(null).isEmpty());
        assertTrue(BookPages.normalize(List.of()).isEmpty());
        assertTrue(BookPages.normalize(List.of(List.of())).isEmpty());
    }

    @Test
    void manyForeignLinesAreChunkedIntoPages() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < BookPages.MAX_LINES + 3; i++) {
            lines.add("line " + i);
        }
        List<List<String>> pages = BookPages.normalize(List.of(lines));
        assertEquals(2, pages.size());
        assertEquals(BookPages.MAX_LINES, pages.get(0).size());
        assertEquals(3, pages.get(1).size());
    }
}
