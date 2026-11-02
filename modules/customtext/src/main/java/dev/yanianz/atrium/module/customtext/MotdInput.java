package dev.yanianz.atrium.module.customtext;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits the argument of {@code /setmotd} into server-list lines. Pure so the limits are unit tested.
 * The performance module applies its own identical limits before it stores the value; this keeps the reply
 * and the stored value consistent.
 */
final class MotdInput {
    static final int MAX_LINES = 2;
    static final int MAX_LINE_LENGTH = 512;
    private static final String LINE_BREAK = "\\n";

    private MotdInput() {}

    static List<String> parse(String typed) {
        if (typed == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(MAX_LINES);
        for (String part : typed.replace("|", LINE_BREAK).split(Pattern.quote(LINE_BREAK), -1)) {
            String line = part.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.length() > MAX_LINE_LENGTH) {
                line = line.substring(0, MAX_LINE_LENGTH);
            }
            out.add(line);
            if (out.size() == MAX_LINES) {
                break;
            }
        }
        return List.copyOf(out);
    }
}
