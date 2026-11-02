package dev.yanianz.atrium.module.customtext;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PageMathTest {
    @Test void pageCountIsAtLeastOne() {
        assertEquals(1, PageMath.pageCount(List.of()));
        assertEquals(1, PageMath.pageCount(null));
        assertEquals(3, PageMath.pageCount(List.of(List.of("a"), List.of("b"), List.of("c"))));
    }

    @Test void clampsLowPages() {
        assertEquals(1, PageMath.clampPage(0, 3));
        assertEquals(1, PageMath.clampPage(-99, 3));
    }

    @Test void clampsHighPages() {
        assertEquals(3, PageMath.clampPage(4, 3));
        assertEquals(3, PageMath.clampPage(Integer.MAX_VALUE, 3));
    }

    @Test void keepsPagesInsideTheRange() {
        assertEquals(1, PageMath.clampPage(1, 3));
        assertEquals(2, PageMath.clampPage(2, 3));
        assertEquals(3, PageMath.clampPage(3, 3));
    }

    @Test void emptyTextStillUsesPageOne() {
        assertEquals(1, PageMath.clampPage(5, 0));
    }
}
