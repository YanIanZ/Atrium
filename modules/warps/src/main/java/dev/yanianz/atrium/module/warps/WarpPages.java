package dev.yanianz.atrium.module.warps;

import java.util.ArrayList;
import java.util.List;

/**
 * Pagination for the /warps chest menu. The menu keeps its bottom row for the page buttons, so a menu of
 * {@code rows} rows shows {@code (rows - 1) * 9} entries per page.
 */
final class WarpPages {
    static final int MIN_ROWS = 3;
    static final int MAX_ROWS = 6;

    private WarpPages() {}

    /** Menu rows clamped to the supported range. */
    static int rows(int configured) {
        return Math.max(MIN_ROWS, Math.min(MAX_ROWS, configured));
    }

    /** Entries per page for a menu of {@code rows} rows. */
    static int pageSize(int rows) {
        return (rows(rows) - 1) * 9;
    }

    /** Never zero: an empty menu still has one (empty) page. */
    static int pageCount(int entries, int pageSize) {
        if (entries <= 0 || pageSize <= 0) {
            return 1;
        }
        return (entries + pageSize - 1) / pageSize;
    }

    static int clamp(int requested, int pages) {
        return Math.max(1, Math.min(requested, Math.max(1, pages)));
    }

    /** Items of {@code page} (1-based); an out-of-range page is clamped, not an error. */
    static <T> List<T> page(List<T> all, int page, int pageSize) {
        if (all.isEmpty() || pageSize <= 0) {
            return List.of();
        }
        int current = clamp(page, pageCount(all.size(), pageSize));
        int from = Math.min((current - 1) * pageSize, all.size());
        int to = Math.min(from + pageSize, all.size());
        return new ArrayList<>(all.subList(from, to));
    }
}
