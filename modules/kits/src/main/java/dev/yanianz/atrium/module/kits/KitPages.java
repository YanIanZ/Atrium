package dev.yanianz.atrium.module.kits;

import java.util.List;

/** Pagination for the kit browser. The bottom row holds the page controls. */
final class KitPages {
    private KitPages() {}

    static int rows(int configured) {
        return Math.clamp(configured, 3, 6);
    }

    static int pageSize(int rows) {
        return Math.max(1, rows(rows) - 1) * 9;
    }

    static int pageCount(int entries, int pageSize) {
        if (entries <= 0 || pageSize <= 0) return 1;
        return (entries + pageSize - 1) / pageSize;
    }

    static int clamp(int requested, int pages) {
        return Math.max(1, Math.min(requested, Math.max(1, pages)));
    }

    static <T> List<T> page(List<T> all, int page, int pageSize) {
        if (all.isEmpty() || pageSize <= 0) return List.of();
        int current = clamp(page, pageCount(all.size(), pageSize));
        int from = Math.min((current - 1) * pageSize, all.size());
        int to = Math.min(from + pageSize, all.size());
        return all.subList(from, to);
    }
}
