package dev.yanianz.atrium.module.moderation;

/**
 * One page of a list, clamped so that asking for page 0 or page 999 never hides the only content.
 * Pure arithmetic, used by every listing command.
 */
record Pagination(int current, int pages, int offset, int total) {
    static Pagination of(int total, int requestedPage, int pageSize) {
        int size = Math.max(1, pageSize);
        int entries = Math.max(0, total);
        int pages = Math.max(1, (entries + size - 1) / size);
        int current = Math.max(1, Math.min(requestedPage, pages));
        return new Pagination(current, pages, (current - 1) * size, entries);
    }

    boolean empty() {
        return total == 0;
    }
}
