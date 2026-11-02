package dev.yanianz.atrium.core.menu;

import java.util.List;

/**
 * Page math for lists shown in fixed-size areas (menus or chat). Pages are 0-based internally; show
 * {@code page + 1} to players. There is always at least one (possibly empty) page.
 */
public record Paginator(int totalItems, int perPage) {
    public Paginator {
        if (perPage <= 0) {
            throw new IllegalArgumentException("perPage must be positive: " + perPage);
        }
        totalItems = Math.max(0, totalItems);
    }

    public int pageCount() {
        return Math.max(1, (totalItems + perPage - 1) / perPage);
    }

    /** The page clamped to {@code [0, pageCount() - 1]}. */
    public int clamp(int page) {
        return Math.max(0, Math.min(page, pageCount() - 1));
    }

    /** Index of the first item on the (clamped) page. */
    public int fromIndex(int page) {
        return Math.min(totalItems, clamp(page) * perPage);
    }

    /** Index after the last item on the (clamped) page. */
    public int toIndex(int page) {
        return Math.min(totalItems, fromIndex(page) + perPage);
    }

    public boolean hasPrevious(int page) {
        return clamp(page) > 0;
    }

    public boolean hasNext(int page) {
        return clamp(page) < pageCount() - 1;
    }

    /** The items of the (clamped) page. The list size must equal {@link #totalItems()}. */
    public <T> List<T> slice(List<T> items, int page) {
        if (items.size() != totalItems) {
            throw new IllegalArgumentException("list has " + items.size() + " items, paginator expects " + totalItems);
        }
        return items.subList(fromIndex(page), toIndex(page));
    }

    /** Convenience: paginator for a list. */
    public static Paginator of(List<?> items, int perPage) {
        return new Paginator(items.size(), perPage);
    }
}
