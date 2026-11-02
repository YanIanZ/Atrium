package dev.yanianz.atrium.module.customtext;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure page/line operations behind {@code /editctext}. Every method returns a new immutable structure
 * and validates the 1-based bounds, so the commands can never throw an index error.
 *
 * <p>A new page contains one empty line because the loader rejects a page with no lines. The last page
 * and the last line of a page cannot be removed: an empty text or an empty page would stop the file from
 * loading on the next reload.
 */
final class TextEditModel {
    private TextEditModel() {}

    /**
     * A refused edit. Carries the reason and the largest value that would have been accepted, so the
     * message can be produced after the file was re-read on the I/O thread (the bounds may have moved
     * since the command was typed).
     */
    static final class TextEditException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final String reason;
        private final int max;

        TextEditException(String reason, int max) {
            super(reason);
            this.reason = reason;
            this.max = max;
        }

        String reason() {
            return reason;
        }

        int max() {
            return max;
        }
    }

    static List<List<String>> addPage(List<List<String>> pages, int index) {
        List<List<String>> copy = copy(pages);
        if (index < 1 || index > copy.size() + 1) {
            throw new TextEditException("page-out-of-range", copy.size() + 1);
        }
        copy.add(index - 1, new ArrayList<>(List.of("")));
        return freeze(copy);
    }

    static List<List<String>> removePage(List<List<String>> pages, int index) {
        List<List<String>> copy = copy(pages);
        if (copy.size() <= 1) {
            throw new TextEditException("last-page", 1);
        }
        if (index < 1 || index > copy.size()) {
            throw new TextEditException("page-out-of-range", copy.size());
        }
        copy.remove(index - 1);
        return freeze(copy);
    }

    static List<List<String>> addLine(List<List<String>> pages, int page, int index, String text) {
        List<List<String>> copy = copy(pages);
        List<String> lines = page(copy, page);
        if (index < 1 || index > lines.size() + 1) {
            throw new TextEditException("line-out-of-range", lines.size() + 1);
        }
        lines.add(index - 1, text == null ? "" : text);
        return freeze(copy);
    }

    static List<List<String>> setLine(List<List<String>> pages, int page, int index, String text) {
        List<List<String>> copy = copy(pages);
        List<String> lines = page(copy, page);
        if (index < 1 || index > lines.size()) {
            throw new TextEditException("line-out-of-range", lines.size());
        }
        lines.set(index - 1, text == null ? "" : text);
        return freeze(copy);
    }

    static List<List<String>> removeLine(List<List<String>> pages, int page, int index) {
        List<List<String>> copy = copy(pages);
        List<String> lines = page(copy, page);
        if (index < 1 || index > lines.size()) {
            throw new TextEditException("line-out-of-range", lines.size());
        }
        if (lines.size() <= 1) {
            throw new TextEditException("last-line", 1);
        }
        lines.remove(index - 1);
        return freeze(copy);
    }

    private static List<String> page(List<List<String>> pages, int page) {
        if (page < 1 || page > pages.size()) {
            throw new TextEditException("page-out-of-range", pages.size());
        }
        return pages.get(page - 1);
    }

    private static List<List<String>> copy(List<List<String>> pages) {
        List<List<String>> copy = new ArrayList<>(pages == null ? 0 : pages.size());
        if (pages != null) {
            for (List<String> page : pages) {
                copy.add(new ArrayList<>(page));
            }
        }
        return copy;
    }

    private static List<List<String>> freeze(List<List<String>> pages) {
        return pages.stream().map(List::copyOf).toList();
    }
}
