package dev.yanianz.atrium.module.itemtools;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns an admin-written text file into book pages. The arithmetic is pure so it is unit tested: a line
 * that is exactly {@link #SEPARATOR} starts a new page, a page holds at most {@link #MAX_LINES} lines and
 * every line is cut at {@link #MAX_LINE_LENGTH} characters (the protocol limit). Trailing empty lines are
 * dropped, empty pages are skipped and the book is capped at {@link #MAX_PAGES} pages.
 *
 * <p>The text is admin content and is rendered as MiniMessage by the caller; nothing here parses or
 * evaluates it.
 */
final class BookPages {
    static final int MAX_PAGES = 100;
    static final int MAX_LINES = 14;
    static final int MAX_LINE_LENGTH = 255;
    static final String SEPARATOR = "---";

    private BookPages() {
    }

    /** Pages of rendered-safe lines; never empty pages, never more than {@link #MAX_PAGES}. */
    static List<List<String>> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return normalize(rawPages(text));
    }

    /**
     * Applies the same limits to pages that came from another format (the customtext YAML): lines are cut,
     * long pages are split, trailing blank lines and empty pages are dropped, and the count is capped.
     */
    static List<List<String>> normalize(List<List<String>> pages) {
        if (pages == null || pages.isEmpty()) {
            return List.of();
        }
        List<List<String>> result = new ArrayList<>();
        for (List<String> rawPage : pages) {
            if (rawPage == null) {
                continue;
            }
            List<String> truncated = new ArrayList<>(rawPage.size());
            for (String line : rawPage) {
                truncated.add(truncate(line == null ? "" : line.strip()));
            }
            for (List<String> chunk : chunks(truncated)) {
                trimTrailingBlank(chunk);
                if (chunk.isEmpty()) {
                    continue;
                }
                result.add(List.copyOf(chunk));
                if (result.size() >= MAX_PAGES) {
                    return List.copyOf(result);
                }
            }
        }
        return List.copyOf(result);
    }

    private static List<List<String>> rawPages(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        List<List<String>> pages = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String raw : normalized.split("\n", -1)) {
            String line = raw.strip();
            if (line.equals(SEPARATOR)) {
                pages.add(current);
                current = new ArrayList<>();
                continue;
            }
            current.add(truncate(line));
        }
        pages.add(current);
        return pages;
    }

    /** Splits one page into chunks that respect the per-page line limit. */
    private static List<List<String>> chunks(List<String> page) {
        if (page.isEmpty()) {
            return List.of(List.of());
        }
        List<List<String>> chunks = new ArrayList<>();
        for (int start = 0; start < page.size(); start += MAX_LINES) {
            chunks.add(page.subList(start, Math.min(page.size(), start + MAX_LINES)));
        }
        return chunks;
    }

    private static void trimTrailingBlank(List<String> page) {
        while (!page.isEmpty() && page.getLast().isBlank()) {
            page.removeLast();
        }
    }

    static String truncate(String line) {
        return line.length() <= MAX_LINE_LENGTH ? line : line.substring(0, MAX_LINE_LENGTH);
    }
}
