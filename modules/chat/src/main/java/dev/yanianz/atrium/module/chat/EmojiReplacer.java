package dev.yanianz.atrium.module.chat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces {@code :shortcode:} tokens in plain player text.
 *
 * <p>Matching is a single left-to-right pass. At every {@code ':'} the longest configured token that
 * starts there wins, so one shortcode can never be replaced inside a longer one. Spans that look like a
 * URL (including paths that contain a colon) are copied unchanged, so a link such as
 * {@code https://example.com/:heart:} keeps its literal form.
 *
 * <p>The replacement text is inserted literally, never re-scanned, and the original player text is never
 * parsed as MiniMessage.
 */
final class EmojiReplacer {
    private static final Pattern URL = Pattern.compile(
            "(?i)\\b(?:https?|ftp)://\\S+|\\bwww\\.\\S+");
    private static final Pattern NAME = Pattern.compile("[a-z0-9_+-]{1,32}");
    private static final int MAX_CODES = 512;

    private EmojiReplacer() {}

    /** One compiled table: token ({@code :name:}) to replacement text, longest token first. */
    record Table(List<Map.Entry<String, String>> entries) {
        static final Table EMPTY = new Table(List.of());

        Table {
            entries = List.copyOf(entries);
        }

        boolean isEmpty() {
            return entries.isEmpty();
        }

        /** The replacement whose token starts at {@code index}, or {@code null}. */
        String match(String text, int index) {
            for (Map.Entry<String, String> entry : entries) {
                String token = entry.getKey();
                if (index + token.length() > text.length() || !text.startsWith(token, index)) {
                    continue;
                }
                int after = index + token.length();
                if (after < text.length() && isWord(text.charAt(after))) {
                    continue;
                }
                return entry.getValue();
            }
            return null;
        }

        int tokenLength(String text, int index) {
            for (Map.Entry<String, String> entry : entries) {
                String token = entry.getKey();
                if (text.startsWith(token, index)) {
                    int after = index + token.length();
                    if (after >= text.length() || !isWord(text.charAt(after))) {
                        return token.length();
                    }
                }
            }
            return 0;
        }
    }

    /** Compiles the config map, skipping invalid names and logging one line per skipped entry. */
    static Table compile(Map<String, String> codes, Consumer<String> warn) {
        if (codes == null || codes.isEmpty()) {
            return Table.EMPTY;
        }
        Map<String, String> byToken = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<String, String> entry : codes.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String name = entry.getKey().strip();
            if (name.startsWith(":")) {
                name = name.substring(1);
            }
            if (name.endsWith(":")) {
                name = name.substring(0, name.length() - 1);
            }
            name = name.toLowerCase(Locale.ROOT);
            String value = entry.getValue();
            if (!NAME.matcher(name).matches()) {
                warn.accept("Ignoring emoji shortcode '" + entry.getKey()
                        + "': names may only use a-z, 0-9, '_', '+' and '-' (maximum 32 characters)");
                continue;
            }
            if (value.isBlank() || hasControl(value)) {
                warn.accept("Ignoring emoji shortcode '" + name + "': replacement is empty or contains control characters");
                continue;
            }
            if (byToken.putIfAbsent(":" + name + ":", value) != null) {
                warn.accept("Ignoring duplicate emoji shortcode '" + name + "'");
                continue;
            }
            if (++count >= MAX_CODES) {
                warn.accept("Only the first " + MAX_CODES + " emoji shortcodes are used");
                break;
            }
        }
        List<Map.Entry<String, String>> sorted = new ArrayList<>(byToken.entrySet());
        // Longest token first so ':a:b:' wins over ':a:' at the same position.
        sorted.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed()
                .thenComparing(Map.Entry::getKey));
        return new Table(sorted);
    }

    /** Replaces every whole shortcode outside URL spans. Pure and safe for untrusted text. */
    static String replace(String text, Table table) {
        if (text == null || text.isEmpty() || table == null || table.isEmpty()) {
            return text == null ? "" : text;
        }
        List<int[]> urls = urlSpans(text);
        StringBuilder out = new StringBuilder(text.length() + 16);
        int i = 0;
        int urlIndex = 0;
        while (i < text.length()) {
            while (urlIndex < urls.size() && urls.get(urlIndex)[1] <= i) {
                urlIndex++;
            }
            if (urlIndex < urls.size() && urls.get(urlIndex)[0] == i) {
                int end = urls.get(urlIndex)[1];
                out.append(text, i, end);
                i = end;
                urlIndex++;
                continue;
            }
            char c = text.charAt(i);
            if (c == ':') {
                String replacement = table.match(text, i);
                if (replacement != null) {
                    out.append(replacement);
                    i += table.tokenLength(text, i);
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static List<int[]> urlSpans(String text) {
        List<int[]> spans = new ArrayList<>();
        Matcher matcher = URL.matcher(text);
        while (matcher.find()) {
            int end = matcher.end();
            // Keep sentence punctuation outside the URL span.
            while (end > matcher.start()) {
                char last = text.charAt(end - 1);
                if (last == '.' || last == ',' || last == ';' || last == ':' || last == '!' || last == '?'
                        || last == ')' || last == ']' || last == '}' || last == '"' || last == '\'') {
                    end--;
                } else {
                    break;
                }
            }
            if (end > matcher.start()) {
                spans.add(new int[] {matcher.start(), end});
            }
        }
        return spans;
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean hasControl(String value) {
        return value.codePoints().anyMatch(cp -> Character.isISOControl(cp));
    }
}
