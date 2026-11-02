package dev.yanianz.atrium.module.commandrules;

import java.util.Locale;
import java.util.function.Function;

/**
 * Turns a typed command line into the canonical command root used as a rule key.
 *
 * <p>Handles a leading slash, extra spaces, a namespace ({@code minecraft:tp}) and case. The resolver is
 * the server command map in production; tests pass a small map. Nothing here touches the Bukkit API, so
 * aliases ({@code /tp}, {@code /teleport}) can be verified without a running server.
 */
public final class CommandLabels {
    private CommandLabels() {}

    /** The first token of a command line, lower case and without leading slashes or arguments. */
    public static String root(String message) {
        String text = message == null ? "" : message.strip();
        while (text.startsWith("/")) {
            text = text.substring(1).stripLeading();
        }
        int end = 0;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        return text.substring(0, end).toLowerCase(Locale.ROOT);
    }

    /** Removes a namespace prefix: {@code minecraft:tp} -> {@code tp}. */
    public static String stripNamespace(String label) {
        int colon = label.indexOf(':');
        return colon >= 0 && colon + 1 < label.length() ? label.substring(colon + 1) : label;
    }

    /**
     * Resolves a typed label to the command root. Tries the full label first (so a plugin can register
     * {@code plugin:alias}), then the label without its namespace. When the command is unknown the bare
     * label is used, which still lets a rule match a command that is registered later.
     */
    public static String canonicalize(String label, Function<String, String> resolver) {
        String value = root(label);
        if (value.isEmpty()) {
            return "";
        }
        String resolved = resolver == null ? null : resolver.apply(value);
        if (resolved == null || resolved.isBlank()) {
            String bare = stripNamespace(value);
            if (!bare.equals(value)) {
                resolved = resolver == null ? null : resolver.apply(bare);
            }
            if (resolved == null || resolved.isBlank()) {
                resolved = bare;
            }
        }
        return stripNamespace(resolved.toLowerCase(Locale.ROOT));
    }
}
