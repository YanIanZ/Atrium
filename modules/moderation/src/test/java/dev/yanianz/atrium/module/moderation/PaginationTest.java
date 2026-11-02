package dev.yanianz.atrium.module.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PaginationTest {
    @Test
    void firstPageStartsAtZero() {
        Pagination page = Pagination.of(25, 1, 10);
        assertEquals(1, page.current());
        assertEquals(3, page.pages());
        assertEquals(0, page.offset());
        assertEquals(25, page.total());
    }

    @Test
    void offsetsFollowTheRequestedPage() {
        assertEquals(10, Pagination.of(25, 2, 10).offset());
        assertEquals(20, Pagination.of(25, 3, 10).offset());
    }

    @Test
    void pageIsClampedToTheLastPage() {
        Pagination page = Pagination.of(25, 99, 10);
        assertEquals(3, page.current());
        assertEquals(20, page.offset());
    }

    @Test
    void pageZeroBecomesOne() {
        assertEquals(1, Pagination.of(5, 0, 10).current());
        assertEquals(0, Pagination.of(5, -4, 10).offset());
    }

    @Test
    void exactMultipleDoesNotCreateAnEmptyPage() {
        Pagination page = Pagination.of(20, 2, 10);
        assertEquals(2, page.pages());
        assertEquals(10, page.offset());
    }

    @Test
    void emptyListStillHasOnePage() {
        Pagination page = Pagination.of(0, 4, 10);
        assertTrue(page.empty());
        assertEquals(1, page.pages());
        assertEquals(1, page.current());
        assertEquals(0, page.offset());
    }

    @Test
    void invalidPageSizeFallsBackToOne() {
        Pagination page = Pagination.of(3, 2, 0);
        assertEquals(3, page.pages());
        assertEquals(1, page.offset());
        assertFalse(page.empty());
    }
}
