package dev.yanianz.atrium.module.customtext;

import java.util.List;

/** Page arithmetic, kept separate so the bounds are easy to test. */
final class PageMath {
    private PageMath() {}

    /** A text always has at least one page, even if a caller hands in an empty list. */
    static int pageCount(List<?> pages) {
        return Math.max(1, pages == null ? 0 : pages.size());
    }

    /** Clamps a requested page into {@code 1..pageCount}; a click on "next" at the last page stays there. */
    static int clampPage(int requested, int pageCount) {
        int count = Math.max(1, pageCount);
        return Math.max(1, Math.min(requested, count));
    }
}
