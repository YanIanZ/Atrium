package dev.yanianz.atrium.module.holograms;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PagesTest {
    @Test void nextAndPreviousWrapAround() {
        assertEquals(1, Pages.next(0, 3));
        assertEquals(2, Pages.next(1, 3));
        assertEquals(0, Pages.next(2, 3));
        assertEquals(2, Pages.previous(0, 3));
        assertEquals(0, Pages.previous(1, 3));
    }

    @Test void singleOrNoPageStaysOnTheFirst() {
        assertEquals(0, Pages.next(0, 1));
        assertEquals(0, Pages.previous(0, 1));
        assertEquals(0, Pages.next(0, 0));
    }

    @Test void removedPageFallsBackToTheFirst() {
        assertEquals(0, Pages.clamp(3, 2));
        assertEquals(0, Pages.clamp(-1, 2));
        assertEquals(1, Pages.clamp(1, 2));
        assertEquals(1, Pages.next(5, 2)); // clamp to 0 first, then advance
        assertEquals(1, Pages.previous(7, 2));
    }

    @Test void fullCycleVisitsEveryPageOnce() {
        int page = 0;
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int i = 0; i < 5; i++) {
            seen.add(page);
            page = Pages.next(page, 5);
        }
        assertEquals(5, seen.size());
        assertEquals(0, page);
    }

    private static final List<List<String>> PAGES = List.of(List.of("a", "b"), List.of(), List.of("c", "d", "e"));

    @Test void globalLineNumbersSpanPages() {
        assertEquals(5, LineIndex.count(PAGES));
        assertEquals(new LineIndex.Position(0, 0), LineIndex.locate(PAGES, 1).orElseThrow());
        assertEquals(new LineIndex.Position(0, 1), LineIndex.locate(PAGES, 2).orElseThrow());
        assertEquals(new LineIndex.Position(2, 0), LineIndex.locate(PAGES, 3).orElseThrow());
        assertEquals(new LineIndex.Position(2, 2), LineIndex.locate(PAGES, 5).orElseThrow());
        assertTrue(LineIndex.locate(PAGES, 0).isEmpty());
        assertTrue(LineIndex.locate(PAGES, 6).isEmpty());
        assertEquals(3, LineIndex.globalNumber(PAGES, 2, 0));
    }

    @Test void insertionAfterTheLastLineAppendsToTheLastPage() {
        assertEquals(new LineIndex.Position(2, 3), LineIndex.insertionPoint(PAGES, 6).orElseThrow());
        assertEquals(new LineIndex.Position(0, 1), LineIndex.insertionPoint(PAGES, 2).orElseThrow());
        assertTrue(LineIndex.insertionPoint(PAGES, 7).isEmpty());
        assertEquals(new LineIndex.Position(0, 0), LineIndex.insertionPoint(List.of(List.of()), 1).orElseThrow());
    }
}
