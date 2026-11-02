package dev.yanianz.atrium.module.customtext;

import java.util.Locale;
import java.util.regex.Pattern;

/** Validation of text ids and command aliases: file names on disk use this same rule. */
final class TextName {
    static final Pattern PATTERN = Pattern.compile("[a-z0-9_-]{1,32}");
    static final String RULE = "lower-case letters, digits, _ and - (1-32 characters)";

    private TextName() {}

    static boolean isValid(String value) {
        return value != null && PATTERN.matcher(value).matches();
    }

    /** Command input is case-insensitive; files themselves must already use the lower-case form. */
    static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
