package dev.yanianz.atrium.module.signs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Window maths for a dynamic sign: a sign has four lines, and a longer list scrolls through them one step per
 * interval. Pure, so the wrap-around (and the "short list does not scroll" rule) is unit tested.
 *
 * <p>{@code _} is a blank line (the admin writes it to leave a gap), matching the long-standing convention.
 */
final class SignScroll {
    private SignScroll() {}

    /** Normalises the configured lines: no control characters, at most 512 lines, {@code _} means blank. */
    static List<String> sanitize(List<String> lines) {
        List<String> result = new ArrayList<>();
        if (lines == null) {
            return result;
        }
        for (String line : lines) {
            String text = line == null ? "" : line.replaceAll("[\\r\\n\\t]", " ").strip();
            if (text.equals("_")) {
                text = "";
            }
            result.add(text);
            if (result.size() >= 512) {
                break;
            }
        }
        return result;
    }

    /** Whether the list is long enough to scroll (a short list is shown as it is, padded with blanks). */
    static boolean scrolls(List<String> lines) {
        return lines != null && lines.size() > 4;
    }

    /** Steps to advance per interval; a short list never advances. */
    static int step(List<String> lines) {
        return scrolls(lines) ? 1 : 0;
    }

    /** The four lines visible at {@code frame}; the list wraps around. */
    static List<String> window(List<String> lines, long frame) {
        List<String> visible = new ArrayList<>(4);
        if (lines == null || lines.isEmpty()) {
            return List.of("", "", "", "");
        }
        int size = lines.size();
        if (size <= 4) {
            // A short sign is shown from the top every time; only a longer list scrolls.
            for (int i = 0; i < 4; i++) {
                visible.add(i < size ? lines.get(i) : "");
            }
            return visible;
        }
        long base = Math.floorMod(frame, size);
        for (int i = 0; i < 4; i++) {
            visible.add(lines.get((int) ((base + i) % size)));
        }
        return visible;
    }

    /** Frame for a moment in time; {@code offset} lets each player start at a different line. */
    static long frame(long nowMillis, long intervalMillis, int offset) {
        long interval = Math.max(50L, intervalMillis);
        return (nowMillis / interval) + Math.floorMod(offset, 1024);
    }

    /** A stable per-player offset so personal signs are not all in step. */
    static int offsetFor(java.util.UUID playerId) {
        return Math.floorMod(playerId.hashCode(), 97);
    }

    /** Splits the text of {@code /dsign lines} on {@code |}. */
    static List<String> split(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return lines;
        }
        for (String part : text.split("\\|", -1)) {
            lines.add(part.strip());
        }
        return lines;
    }

    static String join(List<String> lines) {
        List<String> parts = new ArrayList<>();
        for (String line : lines) {
            parts.add(line.isEmpty() ? "_" : line);
        }
        return String.join(" | ", parts);
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
