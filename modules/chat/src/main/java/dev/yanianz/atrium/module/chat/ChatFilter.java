package dev.yanianz.atrium.module.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Pure text filter. Rules are matched against a normalised copy of the message so colour codes,
 * MiniMessage tags, spacing and simple leetspeak cannot bypass them.
 */
final class ChatFilter {
    private ChatFilter() {}

    enum Action { CENSOR, BLOCK, WARN }

    record Rule(String id, Pattern pattern, Action action, boolean regex) {}

    record Match(String ruleId, Action action, int start, int end) {}

    record Verdict(String text, boolean blocked, List<Match> matches) {
        Verdict {
            matches = List.copyOf(matches);
        }
    }

    /** Normalised text plus the original character range each normalised character came from. */
    record Normalized(String text, int[] starts, int[] ends) {}

    static List<Rule> compile(ChatConfig.Filter config, Consumer<String> warn) {
        if (config == null || !config.enabled || config.rules == null) {
            return List.of();
        }
        List<Rule> rules = new ArrayList<>();
        for (ChatConfig.FilterRule raw : config.rules) {
            if (raw == null || raw.pattern == null || raw.pattern.isBlank()) {
                continue;
            }
            String id = raw.id == null || raw.id.isBlank() ? "rule" : raw.id;
            Action action;
            try {
                action = Action.valueOf(raw.action == null ? "WARN" : raw.action.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                warn.accept("Unknown chat filter action '" + raw.action + "' in rule " + id);
                continue;
            }
            try {
                Pattern pattern;
                if ("WORD".equalsIgnoreCase(raw.type)) {
                    String word = normalize(raw.pattern).text();
                    if (word.isEmpty()) {
                        continue;
                    }
                    pattern = Pattern.compile(Pattern.quote(word));
                    rules.add(new Rule(id, pattern, action, false));
                } else {
                    pattern = Pattern.compile(raw.pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                    rules.add(new Rule(id, pattern, action, true));
                }
            } catch (PatternSyntaxException e) {
                warn.accept("Invalid regex in chat filter rule " + id + ": " + e.getDescription());
            }
        }
        return List.copyOf(rules);
    }

    static Verdict apply(String raw, List<Rule> rules, String replacement) {
        String text = raw == null ? "" : raw;
        if (text.isEmpty() || rules.isEmpty()) {
            return new Verdict(text, false, List.of());
        }
        Normalized wordText = null;
        Normalized regexText = null;
        List<Match> matches = new ArrayList<>();
        boolean blocked = false;
        for (Rule rule : rules) {
            Normalized normalized;
            if (rule.regex()) {
                if (regexText == null) {
                    regexText = normalizeRegex(text);
                }
                normalized = regexText;
            } else {
                if (wordText == null) {
                    wordText = normalize(text);
                }
                normalized = wordText;
            }
            Matcher matcher = rule.pattern().matcher(normalized.text());
            int from = 0;
            while (from <= normalized.text().length()) {
                if (!matcher.find(from)) {
                    break;
                }
                int start = matcher.start();
                int end = matcher.end();
                if (start == end) {
                    from = end + 1;
                    continue;
                }
                matches.add(new Match(rule.id(), rule.action(),
                        normalized.starts()[start], normalized.ends()[end - 1] + 1));
                if (rule.action() == Action.BLOCK) {
                    blocked = true;
                }
                from = end;
            }
        }
        if (matches.isEmpty()) {
            return new Verdict(text, false, List.of());
        }
        if (blocked) {
            return new Verdict(text, true, matches);
        }
        return new Verdict(censor(text, matches, replacement), false, matches);
    }

    private static String censor(String raw, List<Match> matches, String replacement) {
        List<int[]> spans = new ArrayList<>();
        for (Match match : matches) {
            if (match.action() == Action.CENSOR) {
                spans.add(new int[] {Math.max(0, match.start()), Math.min(raw.length(), match.end())});
            }
        }
        if (spans.isEmpty()) {
            return raw;
        }
        spans.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] span : spans) {
            if (span[1] <= span[0]) {
                continue;
            }
            if (!merged.isEmpty() && span[0] <= merged.getLast()[1]) {
                merged.getLast()[1] = Math.max(merged.getLast()[1], span[1]);
            } else {
                merged.add(new int[] {span[0], span[1]});
            }
        }
        String replacementText = replacement == null ? "" : replacement;
        StringBuilder out = new StringBuilder(raw.length());
        int cursor = 0;
        for (int[] span : merged) {
            if (span[0] > cursor) {
                out.append(raw, cursor, span[0]);
            }
            out.append(replacementText);
            cursor = Math.max(cursor, span[1]);
        }
        if (cursor < raw.length()) {
            out.append(raw, cursor, raw.length());
        }
        return out.toString();
    }

    static Normalized normalize(String raw) {
        return normalize(raw, false);
    }

    /** Keeps punctuation between non-letters, so regex rules can still recognise IPs and similar. */
    static Normalized normalizeRegex(String raw) {
        return normalize(raw, true);
    }

    private static Normalized normalize(String raw, boolean keepPunctuation) {
        String input = raw == null ? "" : raw;
        StringBuilder out = new StringBuilder(input.length());
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);

            int skipped = skipFormatting(input, i);
            if (skipped > i) {
                i = skipped;
                continue;
            }
            if (c == '<') {
                int close = input.indexOf('>', i + 1);
                if (close > i + 1 && close - i <= 80 && looksLikeTag(input, i + 1, close)) {
                    i = close + 1;
                    continue;
                }
            }

            char mapped = leet(input, i, !keepPunctuation);
            if (Character.isLetterOrDigit(mapped)) {
                int last = out.length() - 1;
                if (last >= 0 && out.charAt(last) == mapped) {
                    ends.set(last, i);
                } else {
                    out.append(mapped);
                    starts.add(i);
                    ends.add(i);
                }
            } else if (keepPunctuation && keepPunctuation(input, i, out)) {
                out.append(Character.toLowerCase(c));
                starts.add(i);
                ends.add(i);
            }
            i++;
        }
        int[] startArray = new int[starts.size()];
        int[] endArray = new int[ends.size()];
        for (int j = 0; j < starts.size(); j++) {
            startArray[j] = starts.get(j);
            endArray[j] = ends.get(j);
        }
        return new Normalized(out.toString(), startArray, endArray);
    }

    /**
     * Punctuation between two letters is treated as noise and removed. Punctuation between digits (or
     * between a digit and a letter) is kept, otherwise an IPv4 regex could never match after normalising.
     */
    private static boolean keepPunctuation(String input, int index, StringBuilder out) {
        if (out.isEmpty() || !Character.isLetter(out.charAt(out.length() - 1))) {
            return true;
        }
        return !nextIsLetter(input, index + 1, false);
    }

    private static boolean nextIsLetter(String input, int start, boolean mapDigits) {
        int i = start;
        while (i < input.length()) {
            int skipped = skipFormatting(input, i);
            if (skipped > i) {
                i = skipped;
                continue;
            }
            char c = input.charAt(i);
            if (c == '<') {
                int close = input.indexOf('>', i + 1);
                if (close > i + 1 && close - i <= 80 && looksLikeTag(input, i + 1, close)) {
                    i = close + 1;
                    continue;
                }
            }
            return Character.isLetter(leet(input, i, mapDigits));
        }
        return false;
    }

    private static int skipFormatting(String input, int i) {
        char c = input.charAt(i);
        if (c == '\u00a7') {
            return Math.min(input.length(), i + 2);
        }
        if (c != '&' || i + 1 >= input.length()) {
            return i;
        }
        char next = input.charAt(i + 1);
        if (next == '#' && i + 7 < input.length() && isHex(input, i + 2, 6)) {
            return i + 8;
        }
        if ("0123456789abcdefklmnorx".indexOf(Character.toLowerCase(next)) >= 0) {
            return i + 2;
        }
        return i;
    }

    private static boolean isHex(String input, int start, int length) {
        if (start + length > input.length()) {
            return false;
        }
        for (int i = start; i < start + length; i++) {
            if (Character.digit(input.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean looksLikeTag(String input, int start, int end) {
        if (start >= end) {
            return false;
        }
        for (int i = start; i < end; i++) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c)) {
                return false;
            }
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == ':' || c == '/' || c == '#'
                    || c == '.' || c == '!' || c == '?' || c == '=' || c == '&' || c == '\''
                    || c == '"' || c == '-' || c == '\\')) {
                return false;
            }
        }
        return true;
    }

    private static char leet(String input, int index, boolean mapDigits) {
        char c = Character.toLowerCase(input.charAt(index));
        if (!mapDigits && Character.isDigit(c)) {
            return c;
        }
        return switch (c) {
            case '0' -> 'o';
            case '1' -> 'i';
            case '2' -> 'z';
            case '3' -> 'e';
            case '4' -> 'a';
            case '5' -> 's';
            case '6' -> 'g';
            case '7' -> 't';
            case '8' -> 'b';
            case '9' -> 'g';
            case '@' -> hasLetterNeighbour(input, index) ? 'a' : c;
            case '$' -> hasLetterNeighbour(input, index) ? 's' : c;
            case '!' -> betweenLetters(input, index) ? 'i' : c;
            case '|' -> betweenLetters(input, index) ? 'l' : c;
            case '+' -> betweenLetters(input, index) ? 't' : c;
            default -> c;
        };
    }

    private static boolean hasLetterNeighbour(String input, int index) {
        return (index > 0 && Character.isLetter(input.charAt(index - 1)))
                || (index + 1 < input.length() && Character.isLetter(input.charAt(index + 1)));
    }

    private static boolean betweenLetters(String input, int index) {
        return index > 0 && index + 1 < input.length()
                && Character.isLetter(input.charAt(index - 1))
                && Character.isLetter(input.charAt(index + 1));
    }
}
