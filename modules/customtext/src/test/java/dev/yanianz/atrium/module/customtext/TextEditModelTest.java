package dev.yanianz.atrium.module.customtext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Bounds and immutability of the /editctext model operations. */
class TextEditModelTest {
    private static final List<List<String>> TWO_PAGES = List.of(
            List.of("first", "second"),
            List.of("third"));

    @Test
    void addingAPageStartsWithOneEmptyLineAndSupportsBothEnds() {
        List<List<String>> appended = TextEditModel.addPage(TWO_PAGES, 3);
        assertEquals(3, appended.size());
        assertEquals(List.of(""), appended.get(2));

        List<List<String>> prepended = TextEditModel.addPage(TWO_PAGES, 1);
        assertEquals(3, prepended.size());
        assertEquals(List.of(""), prepended.getFirst());
        assertEquals("first", prepended.get(1).getFirst());

        // The last valid insert position is directly after the last page.
        assertEquals(3, TextEditModel.addPage(TWO_PAGES, 3).size());
        assertThrows(IllegalArgumentException.class, () -> TextEditModel.addPage(TWO_PAGES, 4));
        assertThrows(IllegalArgumentException.class, () -> TextEditModel.addPage(TWO_PAGES, 0));
    }

    @Test
    void removingAPageShiftsTheRestAndKeepsTheLastPage() {
        assertEquals(List.of(List.of("third")), TextEditModel.removePage(TWO_PAGES, 1));
        assertEquals(List.of(List.of("first", "second")), TextEditModel.removePage(TWO_PAGES, 2));
        assertThrows(IllegalArgumentException.class, () -> TextEditModel.removePage(List.of(List.of("only")), 1));
        assertThrows(IllegalArgumentException.class, () -> TextEditModel.removePage(TWO_PAGES, 3));
        assertThrows(IllegalArgumentException.class, () -> TextEditModel.removePage(TWO_PAGES, 0));
    }

    @Test
    void lineOperationsValidateThePageAndLine() {
        List<List<String>> added = TextEditModel.addLine(TWO_PAGES, 1, 2, "inserted");
        assertEquals(List.of("first", "inserted", "second"), added.getFirst());
        assertEquals(List.of("third"), added.get(1), "other pages are untouched");

        List<List<String>> set = TextEditModel.setLine(TWO_PAGES, 2, 1, "changed");
        assertEquals(List.of("changed"), set.get(1));

        List<List<String>> removed = TextEditModel.removeLine(TWO_PAGES, 1, 1);
        assertEquals(List.of("second"), removed.getFirst());

        assertThrows(IllegalArgumentException.class, () -> TextEditModel.addLine(TWO_PAGES, 1, 4, "x"));
        assertThrows(IllegalArgumentException.class, () -> TextEditModel.setLine(TWO_PAGES, 1, 3, "x"));
        assertThrows(IllegalArgumentException.class, () -> TextEditModel.removeLine(TWO_PAGES, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> TextEditModel.removeLine(TWO_PAGES, 3, 1));
    }

    @Test
    void aRefusedEditCarriesTheReasonAndTheAcceptedMaximum() {
        TextEditModel.TextEditException page = assertThrows(TextEditModel.TextEditException.class,
                () -> TextEditModel.addPage(TWO_PAGES, 4));
        assertEquals("page-out-of-range", page.reason());
        assertEquals(3, page.max());

        TextEditModel.TextEditException line = assertThrows(TextEditModel.TextEditException.class,
                () -> TextEditModel.setLine(TWO_PAGES, 1, 3, "x"));
        assertEquals("line-out-of-range", line.reason());
        assertEquals(2, line.max());

        TextEditModel.TextEditException last = assertThrows(TextEditModel.TextEditException.class,
                () -> TextEditModel.removePage(List.of(List.of("only")), 1));
        assertEquals("last-page", last.reason());
    }

    @Test
    void theInputModelIsNeverMutated() {
        TextEditModel.addPage(TWO_PAGES, 1);
        TextEditModel.addLine(TWO_PAGES, 1, 1, "x");
        TextEditModel.removeLine(TWO_PAGES, 1, 1);
        assertEquals(List.of("first", "second"), TWO_PAGES.getFirst());
        assertEquals(2, TWO_PAGES.size());
    }
}
