package dev.yanianz.atrium.module.inspect;

import java.util.List;

/** Pure pagination for the read-only lists. Pages are 1-based and an out-of-range page is clamped. */
final class Pages {
    private Pages() {}

    /** Never zero: an empty list still has one (empty) page. */
    static int pageCount(int total, int pageSize) {
        if (total <= 0 || pageSize <= 0) {
            return 1;
        }
        return (total + pageSize - 1) / pageSize;
    }

    static int clamp(int requested, int pages) {
        return Math.max(1, Math.min(requested, Math.max(1, pages)));
    }

    /** Zero-based index of the first entry of {@code page}. */
    static int offset(int page, int pageSize) {
        return (Math.max(1, page) - 1) * Math.max(1, pageSize);
    }

    /** Entries of {@code page}; a page outside the list is clamped, never an error. */
    static <T> List<T> slice(List<T> all, int page, int pageSize) {
        if (all.isEmpty() || pageSize <= 0) {
            return List.of();
        }
        int current = clamp(page, pageCount(all.size(), pageSize));
        int from = Math.min(offset(current, pageSize), all.size());
        int to = Math.min(from + pageSize, all.size());
        return List.copyOf(all.subList(from, to));
    }
}
