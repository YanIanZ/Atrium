package dev.yanianz.atrium.module.search;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class PagesTest {
    @Test void emptyResultsStillHaveOnePage() {
        assertEquals(1, Pages.count(0, 10));
        assertEquals(1, Pages.clamp(5, 0, 10));
        assertTrue(Pages.slice(List.of(), 3, 10).isEmpty());
    }

    @Test void pageCountRoundsUp() {
        assertEquals(1, Pages.count(1, 10));
        assertEquals(1, Pages.count(10, 10));
        assertEquals(2, Pages.count(11, 10));
        assertEquals(10, Pages.count(100, 10));
        assertEquals(1000, Pages.count(1000, 1));
    }

    @Test void slicesAtPageBoundaries() {
        List<Integer> items = List.of(1, 2, 3, 4, 5);
        assertEquals(List.of(1, 2), Pages.slice(items, 1, 2));
        assertEquals(List.of(3, 4), Pages.slice(items, 2, 2));
        assertEquals(List.of(5), Pages.slice(items, 3, 2));
    }

    @Test void outOfRangePagesAreClampedInsteadOfThrowing() {
        List<Integer> items = List.of(1, 2, 3, 4, 5);
        assertEquals(1, Pages.clamp(0, 5, 2));
        assertEquals(1, Pages.clamp(-9, 5, 2));
        assertEquals(3, Pages.clamp(99, 5, 2));
        assertEquals(List.of(5), Pages.slice(items, Integer.MAX_VALUE, 2));
    }

    @Test void rejectsNonPositivePageSize() {
        assertThrows(IllegalArgumentException.class, () -> Pages.count(5, 0));
        assertThrows(IllegalArgumentException.class, () -> Pages.clamp(1, 5, -1));
        assertThrows(IllegalArgumentException.class, () -> Pages.slice(List.of(1), 1, 0));
    }
}
