package dev.yanianz.atrium.module.economy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The optional world-group selector of economy commands: {@code g:<name>} or {@code --group <name>},
 * anywhere among the trailing words. Pure parsing; whether the sender may use it is checked by the caller.
 *
 * @param rest  the remaining words joined by single spaces (empty when nothing else was given)
 * @param group the requested group name, or {@code null} when no selector was given
 */
record GroupOption(String rest, String group) {
    static final GroupOption NONE = new GroupOption("", null);

    /** Parses trailing text; throws {@link IllegalArgumentException} for a malformed or repeated selector. */
    static GroupOption parse(String text) {
        if (text == null || text.isBlank()) return NONE;
        String[] words = text.trim().split("\\s+");
        List<String> rest = new ArrayList<>();
        String group = null;
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            String lower = word.toLowerCase(Locale.ROOT);
            String found = null;
            if (lower.startsWith("g:")) {
                found = word.substring(2);
            } else if (lower.equals("--group")) {
                if (i + 1 >= words.length) throw new IllegalArgumentException("--group needs a group name");
                found = words[++i];
            } else if (lower.startsWith("--group=")) {
                found = word.substring("--group=".length());
            }
            if (found == null) {
                rest.add(word);
                continue;
            }
            if (found.isBlank()) throw new IllegalArgumentException("Empty group name");
            if (group != null) throw new IllegalArgumentException("Only one group may be given");
            group = found;
        }
        return new GroupOption(String.join(" ", rest), group);
    }
}
