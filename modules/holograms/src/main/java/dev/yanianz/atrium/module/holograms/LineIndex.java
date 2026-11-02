package dev.yanianz.atrium.module.holograms;

import java.util.List;
import java.util.Optional;

/**
 * Line numbers in commands are global and 1-based across all pages in display order: page 1 holds lines
 * 1..k, page 2 starts at k+1. {@code /holo info} prints them.
 */
final class LineIndex {
    private LineIndex() {}

    /** 0-based page and line position. */
    record Position(int page, int index) {}

    static int count(List<? extends List<?>> pages) {
        return pages.stream().mapToInt(List::size).sum();
    }

    /** The existing line with this global number. */
    static Optional<Position> locate(List<? extends List<?>> pages, int line) {
        if (line < 1) {
            return Optional.empty();
        }
        int remaining = line - 1;
        for (int page = 0; page < pages.size(); page++) {
            int size = pages.get(page).size();
            if (remaining < size) {
                return Optional.of(new Position(page, remaining));
            }
            remaining -= size;
        }
        return Optional.empty();
    }

    /**
     * Where a line inserted "before global line {@code line}" goes. {@code count + 1} appends to the last page.
     */
    static Optional<Position> insertionPoint(List<? extends List<?>> pages, int line) {
        int total = count(pages);
        if (line == total + 1) {
            int last = pages.size() - 1;
            return Optional.of(new Position(last, pages.get(last).size()));
        }
        return locate(pages, line);
    }

    /** Global number of a 0-based position. */
    static int globalNumber(List<? extends List<?>> pages, int page, int index) {
        int before = 0;
        for (int i = 0; i < page; i++) {
            before += pages.get(i).size();
        }
        return before + index + 1;
    }
}
