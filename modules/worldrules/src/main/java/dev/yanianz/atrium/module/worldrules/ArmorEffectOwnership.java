package dev.yanianz.atrium.module.worldrules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Encoding for the player-data key that remembers which potion effects an armor set applied.
 *
 * <p>It survives a crash or restart, so a leftover infinite effect can be removed on the next join
 * instead of becoming permanent. Keys are namespaced registry ids ({@code minecraft:speed}), which never
 * contain a comma, so a comma-separated list is a stable and human-readable format.
 */
final class ArmorEffectOwnership {
    private static final String SEPARATOR = ",";

    private ArmorEffectOwnership() {}

    static String encode(Collection<String> effectKeys) {
        if (effectKeys == null || effectKeys.isEmpty()) {
            return "";
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String key : effectKeys) {
            if (key != null && !key.isBlank()) {
                unique.add(key.strip());
            }
        }
        List<String> sorted = new ArrayList<>(unique);
        sorted.sort(String::compareTo);
        return String.join(SEPARATOR, sorted);
    }

    static List<String> decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String part : encoded.split(SEPARATOR)) {
            String key = part.strip();
            if (!key.isEmpty()) {
                unique.add(key);
            }
        }
        return List.copyOf(unique);
    }
}
