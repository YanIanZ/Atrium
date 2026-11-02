package dev.yanianz.atrium.module.inspect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PagesTest {
    @Test
    void emptyListsStillHaveOnePage() {
        assertEquals(1, Pages.pageCount(0, 10));
        assertEquals(1, Pages.pageCount(0, 0));
        assertEquals(0, Pages.slice(List.of(), 1, 10).size());
    }

    @Test
    void pageCountRoundsUp() {
        assertEquals(1, Pages.pageCount(10, 10));
        assertEquals(2, Pages.pageCount(11, 10));
        assertEquals(3, Pages.pageCount(21, 10));
        assertEquals(5, Pages.pageCount(50, 10));
    }

    @Test
    void requestedPagesAreClamped() {
        assertEquals(1, Pages.clamp(-5, 3));
        assertEquals(1, Pages.clamp(0, 3));
        assertEquals(2, Pages.clamp(2, 3));
        assertEquals(3, Pages.clamp(99, 3));
        assertEquals(1, Pages.clamp(99, 1));
    }

    @Test
    void slicesAreOneBasedAndClamped() {
        List<Integer> all = List.of(1, 2, 3, 4, 5);
        assertEquals(List.of(1, 2), Pages.slice(all, 1, 2));
        assertEquals(List.of(3, 4), Pages.slice(all, 2, 2));
        assertEquals(List.of(5), Pages.slice(all, 3, 2));
        assertEquals(List.of(5), Pages.slice(all, 99, 2));
        assertEquals(List.of(1, 2), Pages.slice(all, -1, 2));
    }

    @Test
    void offsetsMatchThePageStart() {
        assertEquals(0, Pages.offset(1, 10));
        assertEquals(20, Pages.offset(3, 10));
        assertEquals(0, Pages.offset(0, 10));
    }

    @Test
    void sliceIsImmutableAndNeverOutOfBounds() {
        List<Integer> all = List.of(1, 2, 3);
        List<Integer> page = Pages.slice(all, 2, 10);
        assertEquals(List.of(1, 2, 3), page);
        assertEquals(3, all.size());
        assertTrue(Pages.slice(all, 1, 10).contains(3));
    }
}
