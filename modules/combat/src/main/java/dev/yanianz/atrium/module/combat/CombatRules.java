package dev.yanianz.atrium.module.combat;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/** Pure rules for world enablement, command blocking and tag timing. */
final class CombatRules {
    enum Display { NONE, ACTIONBAR, BOSSBAR }

    private CombatRules() {}

    /**
     * Whether combat is enabled in a world. An exact world entry wins (case-insensitive); otherwise the
     * {@code "*"} entry applies; otherwise {@code fallback} is used.
     */
    static boolean worldEnabled(Map<String, Boolean> worlds, String worldName, boolean fallback) {
        if (worlds == null || worlds.isEmpty()) {
            return fallback;
        }
        if (worldName != null) {
            for (Map.Entry<String, Boolean> entry : worlds.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(worldName)) {
                    return entry.getValue() == null ? fallback : entry.getValue();
                }
            }
        }
        Boolean any = worlds.get("*");
        return any == null ? fallback : any;
    }

    /** The display mode, falling back to {@link Display#ACTIONBAR} for unknown values. */
    static Display display(String raw) {
        Display parsed = parseDisplay(raw);
        return parsed == null ? Display.ACTIONBAR : parsed;
    }

    /** The display mode, or {@code null} when the configured value is unknown. */
    static Display parseDisplay(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Display.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The command without its slashes, namespace and arguments, lower-cased. */
    static String commandRoot(String rawLine) {
        String[] words = words(rawLine);
        return words.length == 0 ? "" : words[0];
    }

    /**
     * Whether a raw command line is blocked while tagged. Matching is word by word, so {@code home} blocks
     * {@code /home 2} but not {@code /homeless}. Entries in {@code allowed} always win over {@code blocked}.
     */
    static boolean blocksCommand(String rawLine, Collection<String> blocked, Collection<String> allowed) {
        String[] words = words(rawLine);
        if (words.length == 0) {
            return false;
        }
        if (matchesAny(words, allowed)) {
            return false;
        }
        return matchesAny(words, blocked);
    }

    private static boolean matchesAny(String[] words, Collection<String> patterns) {
        if (patterns == null) {
            return false;
        }
        for (String entry : patterns) {
            String[] pattern = words(entry);
            if (pattern.length == 0 || pattern.length > words.length) {
                continue;
            }
            boolean match = true;
            for (int i = 0; i < pattern.length; i++) {
                if (!words[i].equals(pattern[i])) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    private static String[] words(String raw) {
        if (raw == null) {
            return new String[0];
        }
        String line = raw.trim();
        while (line.startsWith("/")) {
            line = line.substring(1).trim();
        }
        if (line.isEmpty()) {
            return new String[0];
        }
        String[] parts = line.split("\\s+");
        parts[0] = stripNamespace(parts[0]).toLowerCase(Locale.ROOT);
        for (int i = 1; i < parts.length; i++) {
            parts[i] = parts[i].toLowerCase(Locale.ROOT);
        }
        return parts;
    }

    private static String stripNamespace(String command) {
        int colon = command.indexOf(':');
        if (colon >= 0 && colon + 1 < command.length()) {
            return command.substring(colon + 1);
        }
        return command;
    }
}
