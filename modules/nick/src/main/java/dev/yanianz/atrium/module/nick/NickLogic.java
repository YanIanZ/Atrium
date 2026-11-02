package dev.yanianz.atrium.module.nick;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Pure nickname rules: validation, blocked-word normalisation and the whitelist that removes tags a
 * player is no longer allowed to use. No server state is touched here.
 */
final class NickLogic {
    enum Violation {
        LENGTH,
        CHARACTERS,
        BLOCKED
    }

    private static final Pattern TAG = Pattern.compile("<([^<>]*)>");
    private static final Set<String> DECORATIONS = Set.of(
            "obfuscated", "obf", "bold", "b", "strikethrough", "st", "underlined", "u", "italic", "em", "i");
    private static final Set<String> COLOR_ALIASES = Set.of("grey", "dark_grey");

    private NickLogic() {}

    static Violation validate(String plain, NickRules rules) {
        int length = plain.codePointCount(0, plain.length());
        if (length < rules.minLength() || length > rules.maxLength()) {
            return Violation.LENGTH;
        }
        if (!rules.allowed().matcher(plain).matches()) {
            return Violation.CHARACTERS;
        }
        return isBlocked(plain, rules) ? Violation.BLOCKED : null;
    }

    static boolean isBlocked(String plain, NickRules rules) {
        String value = normalizeWord(rules.normalizeBlocked(), plain);
        for (String word : rules.blockedWords()) {
            if (value.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** Lower-case words, optionally folding the usual nickname evasions and dropping separators. */
    static String normalizeWord(boolean normalize, String input) {
        String lower = input == null ? "" : input.toLowerCase(Locale.ROOT);
        if (!normalize) {
            return lower;
        }
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char mapped = switch (lower.charAt(i)) {
                case '4', '@' -> 'a';
                case '0' -> 'o';
                case '1', '!', '|' -> 'i';
                case '3' -> 'e';
                case '5', '$' -> 's';
                case '7' -> 't';
                case '8' -> 'b';
                default -> lower.charAt(i);
            };
            if (Character.isLetterOrDigit(mapped)) {
                out.append(mapped);
            }
        }
        return out.toString();
    }

    static String canonical(String plain) {
        return plain == null ? "" : plain.toLowerCase(Locale.ROOT);
    }

    /** Removes the decorative display prefix from user input used for a lookup. */
    static String stripPrefix(String input, String prefix) {
        String value = input == null ? "" : input.trim();
        if (prefix != null && !prefix.isEmpty() && value.startsWith(prefix)) {
            value = value.substring(prefix.length()).trim();
        }
        return value;
    }

    /**
     * Keeps only the tags the player still has permission for. Used when replaying a stored nickname so a
     * revoked permission cannot resurrect the old style (and never leaves literal tag text on screen).
     */
    static String restrictTags(String raw, boolean color, boolean format, boolean gradient) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        Matcher matcher = TAG.matcher(raw);
        StringBuilder out = new StringBuilder(raw.length());
        int last = 0;
        while (matcher.find()) {
            out.append(raw, last, matcher.start());
            if (tagAllowed(matcher.group(1), color, format, gradient)) {
                out.append(matcher.group());
            }
            last = matcher.end();
        }
        out.append(raw, last, raw.length());
        return out.toString();
    }

    private static boolean tagAllowed(String content, boolean color, boolean format, boolean gradient) {
        String name = content.trim();
        if (name.startsWith("/")) {
            name = name.substring(1).trim();
        }
        if (name.endsWith("/")) {
            name = name.substring(0, name.length() - 1).trim();
        }
        int colon = name.indexOf(':');
        String root = (colon >= 0 ? name.substring(0, colon) : name).toLowerCase(Locale.ROOT);
        if (root.equals("reset")) {
            return color || format || gradient;
        }
        if (root.equals("gradient") || root.equals("rainbow") || root.equals("transition")) {
            return gradient;
        }
        if (root.startsWith("!")) {
            root = root.substring(1);
        }
        if (DECORATIONS.contains(root)) {
            return format;
        }
        if (root.equals("color") || root.equals("colour") || root.equals("c")) {
            return color;
        }
        return color && (root.startsWith("#") || COLOR_ALIASES.contains(root)
                || NamedTextColor.NAMES.value(root) != null);
    }
}
