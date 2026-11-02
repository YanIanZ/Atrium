package dev.yanianz.atrium.module.importer;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Name rules shared by the warp, kit and jail imports. Atrium names are {@code [A-Za-z0-9_-]{1,32}}; a
 * foreign name with other characters (spaces, dots, colour codes, CMI's escaped separators) is turned
 * into a valid name instead of being dropped, and the rename is reported.
 */
final class ImportNames {
    static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private ImportNames() {}

    static boolean valid(String name) {
        return name != null && VALID.matcher(name).matches();
    }

    /**
     * A valid name for {@code raw}, or {@code null} when nothing usable is left. Legacy colour codes are
     * removed, CMI's {@code T7C/T8C/T9C} escapes are decoded first, every other illegal character becomes
     * {@code _}, and the result is cut to 32 characters.
     */
    static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String text = CmiLocations.unescape(raw.strip()).replaceAll("(?i)[&§][0-9a-fk-or]", "");
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean ok = c < 128 && (Character.isLetterOrDigit(c) || c == '_' || c == '-');
            if (ok) {
                out.append(c);
            } else if (out.isEmpty() || out.charAt(out.length() - 1) != '_') {
                out.append('_');
            }
        }
        String cleaned = out.toString().replaceAll("^_+|_+$", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > 32 ? cleaned.substring(0, 32) : cleaned;
    }

    static String lower(String name) {
        return name == null ? null : name.toLowerCase(Locale.ROOT);
    }
}
