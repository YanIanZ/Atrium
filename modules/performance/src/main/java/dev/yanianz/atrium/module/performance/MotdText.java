package dev.yanianz.atrium.module.performance;

import java.util.ArrayList;
import java.util.List;

/**
 * Rules for the two server-list lines. Pure: no MiniMessage and no server access, so the limits are covered by
 * unit tests. MiniMessage itself is applied later by {@link ServerListListener}, which also inserts the numbers.
 */
final class MotdText {
    /** The client shows two lines; a third is ignored by the game. */
    static final int MAX_LINES = 2;
    /** One MOTD line of the protocol is a single chat component; this keeps it readable. */
    static final int MAX_LINE_LENGTH = 512;
    /** Separators accepted on the command line, written as {@code \n} or as a pipe. */
    static final String LINE_BREAK = "\\n";

    private MotdText() {}

    /** Splits a typed argument into lines ({@code \n} or {@code |}) and normalises them. */
    static List<String> parse(String typed) {
        if (typed == null) {
            return List.of();
        }
        List<String> raw = new ArrayList<>();
        for (String part : typed.replace("|", LINE_BREAK).split(java.util.regex.Pattern.quote(LINE_BREAK), -1)) {
            raw.add(part);
        }
        return normalize(raw);
    }

    /**
     * Trims, drops empty lines, keeps at most {@link #MAX_LINES} and cuts each line to
     * {@link #MAX_LINE_LENGTH} characters. Also used for values read from disk, so a hand-edited file cannot
     * produce a broken MOTD.
     */
    static List<String> normalize(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(MAX_LINES);
        for (String line : raw) {
            if (line == null) {
                continue;
            }
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() > MAX_LINE_LENGTH) {
                trimmed = trimmed.substring(0, MAX_LINE_LENGTH);
            }
            out.add(trimmed);
            if (out.size() == MAX_LINES) {
                break;
            }
        }
        return List.copyOf(out);
    }

    /** One stored line with the numbers substituted, for the ping event. */
    static String withNumbers(String template, int online, int max) {
        return template.replace("<online>", Integer.toString(online)).replace("<max>", Integer.toString(max));
    }
}
