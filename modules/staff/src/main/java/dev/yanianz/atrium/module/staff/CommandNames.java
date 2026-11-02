package dev.yanianz.atrium.module.staff;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Matches a typed command against a configured list of command names. Pure, unit-tested.
 *
 * <p>The typed label is normalised (leading slash, {@code namespace:} prefix and case removed) and expanded
 * with every name the server knows for that command (its real name and aliases), so {@code /Minecraft:W},
 * {@code /whisper} and {@code /tell} all match a list that contains {@code msg} when the server reports
 * them as aliases of one command.
 */
final class CommandNames {
    private CommandNames() {
    }

    /** First word of a command line, without the leading slash; empty for a blank line. */
    static String label(@Nullable String commandLine) {
        if (commandLine == null) {
            return "";
        }
        String body = commandLine.strip();
        while (body.startsWith("/")) {
            body = body.substring(1).stripLeading();
        }
        int split = 0;
        while (split < body.length() && !Character.isWhitespace(body.charAt(split))) {
            split++;
        }
        return body.substring(0, split);
    }

    /** {@code "/Plugin:Login"} → {@code "login"}. */
    static String normalize(@Nullable String name) {
        if (name == null) {
            return "";
        }
        String value = name.strip();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        int colon = value.lastIndexOf(':');
        if (colon >= 0) {
            value = value.substring(colon + 1);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    static Set<String> normalizedSet(@Nullable Collection<String> names) {
        Set<String> set = new HashSet<>();
        if (names != null) {
            for (String name : names) {
                String normalized = normalize(name);
                if (!normalized.isEmpty()) {
                    set.add(normalized);
                }
            }
        }
        return set;
    }

    /**
     * @param label    the typed label (may carry a namespace)
     * @param aliases  every known name of the command behind {@code label} (name, label, aliases); may be empty
     * @param listed   the configured names
     */
    static boolean matches(String label, Function<String, Collection<String>> aliases, Collection<String> listed) {
        Set<String> wanted = normalizedSet(listed);
        String normalized = normalize(label);
        if (normalized.isEmpty() || wanted.isEmpty()) {
            return false;
        }
        if (wanted.contains(normalized)) {
            return true;
        }
        Collection<String> known = aliases.apply(label);
        if (known == null) {
            return false;
        }
        for (String name : known) {
            if (wanted.contains(normalize(name))) {
                return true;
            }
        }
        return false;
    }
}
