package dev.yanianz.atrium.module.itemtools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.yanianz.atrium.module.itemtools.BookCopy.Outcome;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BookCopyTest {
    @Test
    void neverCopiesWithoutAnEmptyBookToConsume() {
        // Regression: /getbook used to hand out a new writable book every time (unlimited book and quills).
        assertEquals(Outcome.NO_BLANK_BOOK, BookCopy.decide(true, true, false, -1));
        assertEquals(Outcome.NO_BLANK_BOOK, BookCopy.decide(true, false, true, -1));
    }

    @Test
    void copiesOwnBooksIntoAnEmptyBook() {
        assertEquals(Outcome.COPY, BookCopy.decide(true, true, false, 0));
        assertEquals(Outcome.COPY, BookCopy.decide(true, true, false, 35));
    }

    @Test
    void booksOfOthersNeedThePermission() {
        assertEquals(Outcome.DENIED, BookCopy.decide(true, false, false, 3));
        assertEquals(Outcome.COPY, BookCopy.decide(true, false, true, 3));
    }

    @Test
    void requiresAWrittenBookFirst() {
        assertEquals(Outcome.NOT_BOOK, BookCopy.decide(false, true, true, 2));
        assertEquals(Outcome.NOT_BOOK, BookCopy.decide(false, false, false, -1));
    }

    @Test
    void findsTheFirstEmptyBook() {
        assertEquals(-1, BookCopy.firstBlank(36, slot -> false));
        assertEquals(4, BookCopy.firstBlank(36, Set.of(4, 9)::contains));
        assertEquals(-1, BookCopy.firstBlank(36, slot -> slot >= 36));
        assertEquals(-1, BookCopy.firstBlank(0, slot -> true));
    }
}
