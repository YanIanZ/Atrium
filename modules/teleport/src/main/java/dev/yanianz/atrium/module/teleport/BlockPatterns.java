package dev.yanianz.atrium.module.teleport;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Material names from the configuration, matched against a block or item name. Patterns are
 * case-insensitive and support {@code *} anywhere, so {@code "*_SIGN"} matches every sign and
 * {@code "OAK_*"} every oak block. A pattern without {@code *} must match the name exactly.
 */
final class BlockPatterns {
    private BlockPatterns() {}

    static boolean matches(List<String> patterns, String name) {
        if (patterns == null || patterns.isEmpty() || name == null || name.isEmpty()) {
            return false;
        }
        String candidate = name.trim().toUpperCase(Locale.ROOT);
        for (String raw : patterns) {
            if (raw == null) {
                continue;
            }
            String pattern = raw.trim().toUpperCase(Locale.ROOT);
            if (pattern.isEmpty()) {
                continue;
            }
            if (pattern.indexOf('*') < 0) {
                if (candidate.equals(pattern)) {
                    return true;
                }
                continue;
            }
            if (glob(pattern).matcher(candidate).matches()) {
                return true;
            }
        }
        return false;
    }

    /** Case-insensitive glob with {@code *} as "any run of characters". */
    private static Pattern glob(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (String part : pattern.split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(part));
        }
        return Pattern.compile(regex.toString());
    }

    /** World names from the configuration; an empty list means every world. */
    static boolean worldAllowed(List<String> worlds, String worldName) {
        if (worlds == null || worlds.isEmpty()) {
            return true;
        }
        return worldName != null
                && worlds.stream().anyMatch(w -> w != null && w.trim().equalsIgnoreCase(worldName));
    }
}
