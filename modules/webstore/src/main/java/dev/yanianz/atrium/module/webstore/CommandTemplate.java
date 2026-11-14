package dev.yanianz.atrium.module.webstore;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Placeholder substitution for package command lines: {@code {uuid}}, {@code {player}}, {@code {buyer}} and
 * {@code {package}}. Values come from rows written by the website, so before a value is put into a console
 * command it must match a strict pattern; otherwise the delivery is refused instead of running a command whose
 * arguments someone else chose. Substitution is a single pass (a value is never substituted again); unknown
 * placeholders are left as they are.
 */
final class CommandTemplate {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");
    private static final Pattern UUID_PATTERN = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern BUYER_NAME = Pattern.compile("[A-Za-z0-9_.\\-]{1,64}");
    private static final Pattern PACKAGE_ID = Pattern.compile("[A-Za-z0-9_.\\-]{1,64}");

    /** A value that is missing or not safe to put into a command. */
    static final class UnsafeValueException extends Exception {
        private static final long serialVersionUID = 1L;
        private final String placeholder;

        UnsafeValueException(String placeholder) {
            super("unsafe or missing value for {" + placeholder + "}");
            this.placeholder = placeholder;
        }

        String placeholder() {
            return placeholder;
        }
    }

    private CommandTemplate() {}

    /** The placeholder values of one delivery. {@code uuid} is lower-cased; {@code buyer} may be null. */
    static Map<String, @Nullable String> values(String uuid, String player, @Nullable String buyer, String packageId) {
        HashMap<String, @Nullable String> map = new HashMap<>();
        map.put("uuid", uuid == null ? null : uuid.toLowerCase(Locale.ROOT));
        map.put("player", player);
        map.put("buyer", buyer);
        map.put("package", packageId);
        return map;
    }

    /** True when {@code value} may be inserted into a command for {@code placeholder}. */
    static boolean safe(String placeholder, @Nullable String value) {
        if (value == null) {
            return false;
        }
        return switch (placeholder) {
            case "uuid" -> UUID_PATTERN.matcher(value).matches();
            case "player" -> PLAYER_NAME.matcher(value).matches();
            case "buyer" -> BUYER_NAME.matcher(value).matches();
            case "package" -> PACKAGE_ID.matcher(value).matches();
            default -> false;
        };
    }

    /**
     * Substitutes the known placeholders in one command line. Throws when a placeholder that is used has a
     * missing or unsafe value. A leading {@code /} is removed.
     */
    static String apply(String template, Map<String, @Nullable String> values) throws UnsafeValueException {
        String line = template.strip();
        if (line.startsWith("/")) {
            line = line.substring(1);
        }
        Matcher matcher = PLACEHOLDER.matcher(line);
        StringBuilder out = new StringBuilder(line.length() + 32);
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement;
            if (values.containsKey(key)) {
                String value = values.get(key);
                if (!safe(key, value)) {
                    throw new UnsafeValueException(key);
                }
                replacement = value;
            } else {
                replacement = matcher.group();
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** True for lines that are skipped: empty or a {@code #} comment. */
    static boolean ignored(@Nullable String line) {
        return line == null || line.isBlank() || line.strip().startsWith("#");
    }
}
