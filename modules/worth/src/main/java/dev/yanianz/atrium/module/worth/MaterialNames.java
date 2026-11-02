package dev.yanianz.atrium.module.worth;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves material names written by other plugins. Modern names are looked up directly; pre-1.13 names
 * without underscores ({@code diamondpickaxe}) are matched against a table of material names with their
 * separators removed, and only when the match is unique. Pure, so it can be tested without a server.
 */
final class MaterialNames {
    private MaterialNames() {}

    static String strip(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 'a' && c <= 'z') {
                out.append((char) (c - 'a' + 'A'));
            } else if (c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Stripped name to the lower-case material names that share it; more than one entry is ambiguous. */
    static Map<String, List<String>> index(Collection<String> materialNames) {
        Map<String, List<String>> map = new HashMap<>();
        for (String name : materialNames) {
            map.computeIfAbsent(strip(name), key -> new ArrayList<>()).add(name.toLowerCase(Locale.ROOT));
        }
        return Map.copyOf(map);
    }

    static Optional<String> unique(Map<String, List<String>> index, String legacyName) {
        List<String> matches = index.get(strip(legacyName));
        if (matches == null || matches.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(matches.getFirst());
    }
}
