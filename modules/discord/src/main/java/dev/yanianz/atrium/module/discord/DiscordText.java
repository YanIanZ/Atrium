package dev.yanianz.atrium.module.discord;

import java.util.Map;

/**
 * Text handling for Discord messages.
 *
 * <p>Discord renders markdown, so text written by players is escaped before it reaches a message. The same
 * pass collapses line breaks (a player must not be able to fake a second line) and breaks {@code ://} with a
 * zero-width space so bare URLs are not turned into clickable previews.
 */
public final class DiscordText {
    public static final int CONTENT_LIMIT = 2000;
    public static final int USERNAME_LIMIT = 80;

    private static final char ZERO_WIDTH_SPACE = '\u200B';

    private DiscordText() {}

    /** Escapes markdown, removes line breaks and neutralises link syntax. Safe for untrusted text. */
    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\', '*', '_', '~', '`', '>', '|', '[', ']' -> out.append('\\').append(c);
                case '\r' -> {
                    // part of a CRLF pair; the LF becomes the single space
                }
                case '\n', '\t' -> appendSpace(out);
                case ':' -> {
                    out.append(':');
                    if (i + 2 < text.length() && text.charAt(i + 1) == '/' && text.charAt(i + 2) == '/') {
                        out.append(ZERO_WIDTH_SPACE);
                    }
                }
                default -> {
                    if (c < 0x20) {
                        appendSpace(out);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /**
     * Single-pass template substitution. Placeholders in {@code template} are replaced by values from
     * {@code fields}; inserted values are never scanned again, so a message containing {@code {actor}} cannot
     * pull in another field.
     *
     * @param escapeValues {@code true} for untrusted fields (markdown escaped), {@code false} for usernames
     */
    public static String render(String template, Map<String, String> fields, boolean escapeValues) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(template.length() + 32);
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{') {
                int end = template.indexOf('}', i + 1);
                if (end > i + 1) {
                    String key = template.substring(i + 1, end);
                    String value = fields.get(key);
                    if (value != null) {
                        out.append(escapeValues ? sanitize(value) : plain(value));
                        i = end + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** Removes control characters and line breaks without touching markdown (used for names/URLs). */
    public static String plain(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t' || c < 0x20 || c == 0x7F) {
                appendSpace(out);
            } else {
                out.append(c);
            }
        }
        return out.toString().strip();
    }

    private static void appendSpace(StringBuilder out) {
        if (out.isEmpty() || out.charAt(out.length() - 1) != ' ') {
            out.append(' ');
        }
    }

    /**
     * Truncates to {@code max} UTF-16 units, appending an ellipsis when text is cut. A surrogate pair is never
     * split, so the result stays valid UTF-16.
     */
    public static String truncate(String text, int max) {
        if (text == null || text.isEmpty() || max <= 0) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        int cut = max - 1;
        if (cut > 0 && cut < text.length()
                && Character.isHighSurrogate(text.charAt(cut - 1))
                && Character.isLowSurrogate(text.charAt(cut))) {
            cut--;
        }
        return text.substring(0, cut) + "…";
    }

    public static String limitContent(String content) {
        return truncate(content, CONTENT_LIMIT);
    }

    public static String limitUsername(String username) {
        return truncate(plain(username), USERNAME_LIMIT);
    }
}
