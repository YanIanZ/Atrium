package dev.yanianz.atrium.module.moderation;

import java.util.OptionalInt;

/**
 * Book page limits: a player may keep at most {@code atrium.moderation.book.pages.N} pages; the default is
 * configured and a player with the {@code .unlimited} node is never limited. The rules are pure so the
 * trimming can be tested without a server.
 */
final class BookLimits {
    static final String PREFIX = "atrium.moderation.book.pages";

    private BookLimits() {}

    /**
     * Pages allowed for one player.
     *
     * @param highest   highest numeric node the player holds, if any (a lower number lowers the limit)
     * @param unlimited whether the player holds {@code .unlimited}
     * @param fallback  configured default; {@code 0} or less means "do not limit"
     */
    static int resolve(OptionalInt highest, boolean unlimited, int fallback) {
        if (unlimited) {
            return Integer.MAX_VALUE;
        }
        if (highest.isPresent()) {
            return Math.max(0, highest.getAsInt());
        }
        return fallback <= 0 ? Integer.MAX_VALUE : fallback;
    }

    static boolean exceeded(int pages, int limit) {
        return limit != Integer.MAX_VALUE && pages > limit;
    }

    /** Number of pages to keep: the limit when it is exceeded, otherwise the current count. */
    static int keep(int pages, int limit) {
        if (pages <= 0) {
            return 0;
        }
        return exceeded(pages, limit) ? Math.max(0, limit) : pages;
    }
}
