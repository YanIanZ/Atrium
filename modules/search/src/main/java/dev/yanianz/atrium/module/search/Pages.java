package dev.yanianz.atrium.module.search;

import java.util.List;
import java.util.Objects;

/**
 * 1-based pagination maths shared by the search result list. Pure logic, unit tested.
 * An empty result set still has one (empty) page so a page number always renders.
 */
public final class Pages {
    private Pages() {}

    public static int count(int total, int pageSize) {
        requirePageSize(pageSize);
        if (total <= 0) {
            return 1;
        }
        return (total + pageSize - 1) / pageSize;
    }

    /** Clamps a requested page into {@code 1..count}. */
    public static int clamp(int page, int total, int pageSize) {
        return Math.max(1, Math.min(page, count(total, pageSize)));
    }

    /** The slice for a page; an out-of-range page is clamped instead of throwing. */
    public static <T> List<T> slice(List<T> items, int page, int pageSize) {
        Objects.requireNonNull(items, "items");
        int safe = clamp(page, items.size(), pageSize);
        // Long arithmetic keeps huge page numbers from overflowing the int range.
        int from = (int) Math.min(items.size(), (long) (safe - 1) * pageSize);
        int to = Math.min(items.size(), from + pageSize);
        return items.subList(from, to);
    }

    private static void requirePageSize(int pageSize) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
    }
}
