package dev.yanianz.atrium.module.scoreboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Minimal set of sidebar operations to go from the lines currently shown to new lines. Line {@code i} of
 * {@code n} uses score {@code n - i}; when the line count changes every remaining line needs a new score.
 */
final class LineDiff {
    enum Kind { ADD, UPDATE, RESCORE, REMOVE }

    /** {@code score} is the score the line must have afterwards (unused for REMOVE). */
    record Change(Kind kind, int index, int score) {}

    private LineDiff() {}

    static <T> List<Change> diff(List<T> shown, List<T> next) {
        List<Change> changes = new ArrayList<>();
        int size = next.size();
        boolean resized = shown.size() != size;
        for (int i = 0; i < size; i++) {
            int score = size - i;
            if (i >= shown.size()) {
                changes.add(new Change(Kind.ADD, i, score));
            } else if (!Objects.equals(shown.get(i), next.get(i))) {
                changes.add(new Change(Kind.UPDATE, i, score));
            } else if (resized) {
                changes.add(new Change(Kind.RESCORE, i, score));
            }
        }
        for (int i = size; i < shown.size(); i++) {
            changes.add(new Change(Kind.REMOVE, i, 0));
        }
        return changes;
    }

    /** Score holder name of line {@code index}; never shown because every line has a custom name. */
    static String entry(int index) {
        return "atrium-line-" + index;
    }
}
