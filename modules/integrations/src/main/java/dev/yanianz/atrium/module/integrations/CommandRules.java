package dev.yanianz.atrium.module.integrations;

import java.util.Collection;
import java.util.Locale;

/** WorldGuard command-flag matching, kept pure for tests. */
final class CommandRules {
    private CommandRules() {}

    static boolean allowed(String rawCommand, Collection<String> blocked, Collection<String> allowed) {
        String command = normalize(rawCommand);
        if (command.isEmpty()) {
            return true;
        }
        if (allowed != null && !allowed.isEmpty() && !matches(command, allowed)) {
            return false;
        }
        return blocked == null || blocked.isEmpty() || !matches(command, blocked);
    }

    private static boolean matches(String command, Collection<String> entries) {
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String pattern = normalize(entry);
            if (pattern.equals("*") || pattern.equals(command)) {
                return true;
            }
            if (pattern.endsWith("*") && command.startsWith(pattern.substring(0, pattern.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String out = value.trim();
        while (out.startsWith("/")) {
            out = out.substring(1);
        }
        int space = out.indexOf(' ');
        if (space >= 0) {
            out = out.substring(0, space);
        }
        return out.toLowerCase(Locale.ROOT);
    }
}
