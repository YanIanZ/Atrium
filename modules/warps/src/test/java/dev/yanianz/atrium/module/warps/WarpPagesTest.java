package dev.yanianz.atrium.module.warps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class WarpPagesTest {
    @Test
    void rowsAreClampedAndKeepTheBottomNavRow() {
        assertEquals(3, WarpPages.rows(1));
        assertEquals(3, WarpPages.rows(3));
        assertEquals(5, WarpPages.rows(5));
        assertEquals(6, WarpPages.rows(99));
        assertEquals(18, WarpPages.pageSize(3));
        assertEquals(45, WarpPages.pageSize(6));
    }

    @Test
    void pageCountIsNeverZero() {
        assertEquals(1, WarpPages.pageCount(0, 45));
        assertEquals(1, WarpPages.pageCount(1, 45));
        assertEquals(1, WarpPages.pageCount(45, 45));
        assertEquals(2, WarpPages.pageCount(46, 45));
        assertEquals(3, WarpPages.pageCount(100, 45));
        assertEquals(1, WarpPages.pageCount(10, 0), "a broken page size still has one page");
    }

    @Test
    void pageIsClampedToTheAvailablePages() {
        assertEquals(1, WarpPages.clamp(0, 3));
        assertEquals(1, WarpPages.clamp(-5, 3));
        assertEquals(2, WarpPages.clamp(2, 3));
        assertEquals(3, WarpPages.clamp(8, 3));
        assertEquals(1, WarpPages.clamp(5, 0), "an empty menu has page 1");
    }

    @Test
    void slicesByPageAndHandlesOutOfRange() {
        List<Integer> all = IntStream.rangeClosed(1, 100).boxed().toList();
        assertEquals(List.of(1, 2, 3), WarpPages.page(all, 1, 3));
        assertEquals(List.of(4, 5, 6), WarpPages.page(all, 2, 3));
        assertEquals(List.of(100), WarpPages.page(all, 999, 3), "a high page is clamped to the last page");
        assertEquals(List.of(1, 2, 3), WarpPages.page(all, 0, 3), "a low page is clamped to the first page");
        assertEquals(List.of(97, 98, 99, 100), WarpPages.page(all.subList(96, 100), 1, 45));
        assertTrue(WarpPages.page(List.of(), 1, 45).isEmpty());
        assertTrue(WarpPages.page(all, 1, 0).isEmpty());
    }
}
