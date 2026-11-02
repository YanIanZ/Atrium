package dev.yanianz.atrium.module.customtext;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes PlaceholderAPI tokens ({@code %expansion_param%}) in one pass and escapes every substituted
 * value so it is literal MiniMessage text.
 *
 * <p>A value is never scanned again. The matcher walks the original template, so a resolved value that
 * itself contains {@code %other%} or {@code <red>} is inserted as-is (escaped), not interpreted.
 */
final class PlaceholderSubstitution {
    static final Pattern TOKEN = Pattern.compile("%([^%\\s<>]+)%");

    private PlaceholderSubstitution() {}

    static String substituteOnce(String template, Function<String, String> resolver) {
        Matcher matcher = TOKEN.matcher(template);
        StringBuilder out = new StringBuilder(template.length());
        while (matcher.find()) {
            String value = resolver.apply(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(escapeMiniMessage(value == null ? "" : value)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Escapes the two characters MiniMessage treats specially, so the value can never open a tag. */
    static String escapeMiniMessage(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '<') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }
}
