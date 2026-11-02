package dev.yanianz.atrium.module.chatbubbles;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure wrapping/limiting for a chat bubble. Player text is never parsed as MiniMessage; only whitespace is
 * normalised and the result is turned into literal {@code Component.text} lines by the module.
 */
public final class BubbleText {
    private static final String ELLIPSIS = "\u2026";

    private BubbleText() {}

    /**
     * @param raw          untrusted chat text
     * @param maxLength    maximum characters kept from the whole message
     * @param charsPerLine soft wrap width
     * @param maxLines     maximum number of returned lines
     */
    public static List<String> lines(String raw, int maxLength, int charsPerLine, int maxLines) {
        if (raw == null || maxLines <= 0) {
            return List.of();
        }
        int lengthLimit = Math.max(1, maxLength);
        int width = Math.max(1, charsPerLine);
        String text = raw.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            return List.of();
        }
        if (text.length() > lengthLimit) {
            int end = text.offsetByCodePoints(0, Math.max(0, lengthLimit - 1));
            text = text.substring(0, end).stripTrailing() + ELLIPSIS;
        }
        List<String> wrapped = wrap(text, width);
        if (wrapped.size() <= maxLines) {
            return List.copyOf(wrapped);
        }
        List<String> limited = new ArrayList<>(wrapped.subList(0, maxLines - 1));
        limited.add(clip(String.join(" ", wrapped.subList(maxLines - 1, wrapped.size())), width));
        return List.copyOf(limited);
    }

    private static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        String remaining = text;
        while (!remaining.isEmpty()) {
            if (remaining.length() <= width) {
                lines.add(remaining);
                break;
            }
            int split = remaining.lastIndexOf(' ', width);
            if (split <= 0) {
                split = width;
            }
            String line = remaining.substring(0, split).stripTrailing();
            if (line.isEmpty()) {
                line = remaining.substring(0, width);
                split = width;
            }
            lines.add(line);
            remaining = remaining.substring(split).stripLeading();
        }
        return lines;
    }

    private static String clip(String text, int width) {
        if (text.length() <= width) {
            return text;
        }
        int end = Math.max(0, width - 1);
        return text.substring(0, end).stripTrailing() + ELLIPSIS;
    }
}
