package dev.yanianz.atrium.module.dialogs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Resolves {@code extends: <other-dialog>} between dialog files. Pure: it works on the raw YAML tree
 * ({@code Map}/{@code List}/scalars) read by {@link DialogLoader}, never on a {@code ConfigurationNode}, so the
 * rules are unit tested without touching the disk.
 *
 * <p>Merging, from the furthest ancestor down to the file itself:
 * <ul>
 *   <li>every scalar and single value ({@code title}, {@code permission}, {@code exit}, ...): the nearest file
 *       wins; a key the child does not mention is inherited;</li>
 *   <li>{@code body}, {@code buttons} and {@code on-open} are concatenated (parent first), which is how a shared
 *       header and a shared footer are expressed;</li>
 *   <li>{@code inputs} are merged by {@code key}: a child input with the same key replaces the parent's one in
 *       place, so a shared dialog can retune a single field without repeating the others.</li>
 * </ul>
 *
 * <p>The {@code extends} key is removed from the result. Cycles (including a file extending itself) and unknown
 * parents are errors; nothing is ever merged twice.
 */
final class DialogInheritance {
    static final String EXTENDS = "extends";
    /** Hard limit so a hand-written chain cannot recurse for long; a cycle is detected far earlier. */
    static final int MAX_DEPTH = 16;
    private static final Set<String> CONCATENATED = Set.of("body", "buttons", "on-open");

    private DialogInheritance() {}

    /**
     * @param id    dialog the tree belongs to
     * @param trees every readable file, dialog id to raw YAML tree
     * @return the tree with every inherited value applied; never contains {@code extends}
     * @throws DialogSyntaxException when the chain has a cycle, is too deep or names a missing dialog
     */
    static Map<String, Object> resolve(String id, Map<String, Map<String, Object>> trees)
            throws DialogSyntaxException {
        List<Map<String, Object>> chain = chain(id, trees);
        Map<String, Object> merged = new LinkedHashMap<>();
        // chain is [child, parent, grandparent, ...]: merge from the furthest ancestor down.
        for (int i = chain.size() - 1; i >= 0; i--) {
            merged = merge(merged, chain.get(i));
        }
        merged.remove(EXTENDS);
        return merged;
    }

    private static List<Map<String, Object>> chain(String id, Map<String, Map<String, Object>> trees)
            throws DialogSyntaxException {
        List<Map<String, Object>> chain = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String current = id;
        while (true) {
            if (!seen.add(current)) {
                throw new DialogSyntaxException(List.of("extends: cycle " + cycle(seen, current)));
            }
            Map<String, Object> tree = trees.get(current);
            if (tree == null) {
                throw new DialogSyntaxException(List.of(
                        "extends: unknown dialog '" + current + "' (no file " + current + ".yml)"));
            }
            chain.add(tree);
            Object parent = tree.get(EXTENDS);
            if (parent == null) {
                return chain;
            }
            if (!(parent instanceof String text) || text.isBlank()) {
                throw new DialogSyntaxException(List.of(
                        "extends: must be the name of another dialog (got " + describe(parent) + ")"));
            }
            String next = text.trim().toLowerCase(Locale.ROOT);
            if (!DialogParser.DIALOG_ID.matcher(next).matches()) {
                throw new DialogSyntaxException(List.of("extends: '" + next + "' is not a valid dialog name"));
            }
            if (chain.size() >= MAX_DEPTH) {
                throw new DialogSyntaxException(List.of("extends: chain longer than " + MAX_DEPTH + " dialogs"));
            }
            current = next;
        }
    }

    /** {@code a -> b -> a}: the part of the chain that closes the loop. */
    private static String cycle(Set<String> seen, String repeated) {
        List<String> names = new ArrayList<>(seen);
        int from = names.indexOf(repeated);
        List<String> loop = from < 0 ? names : names.subList(from, names.size());
        return String.join(" -> ", loop) + " -> " + repeated;
    }

    private static Map<String, Object> merge(Map<String, Object> parent, Map<String, Object> child) {
        Map<String, Object> out = new LinkedHashMap<>(parent);
        for (Map.Entry<String, Object> entry : child.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key.equals(EXTENDS)) {
                continue;
            }
            if (CONCATENATED.contains(key)) {
                List<Object> combined = new ArrayList<>(asList(parent.get(key)));
                combined.addAll(asList(value));
                out.put(key, combined);
            } else if (key.equals("inputs")) {
                out.put(key, mergeInputs(asList(parent.get(key)), asList(value)));
            } else {
                out.put(key, value);
            }
        }
        return out;
    }

    /** Parent inputs in order; a child input with the same key replaces the parent's one where it stood. */
    private static List<Object> mergeInputs(List<Object> parent, List<Object> child) {
        List<Object> out = new ArrayList<>(parent);
        for (Object entry : child) {
            String key = keyOf(entry);
            int existing = key == null ? -1 : indexOfKey(out, key);
            if (existing >= 0) {
                out.set(existing, entry);
            } else {
                out.add(entry);
            }
        }
        return out;
    }

    private static @Nullable String keyOf(Object entry) {
        return entry instanceof Map<?, ?> map && map.get("key") instanceof String key ? key : null;
    }

    private static int indexOfKey(List<Object> entries, String key) {
        for (int i = 0; i < entries.size(); i++) {
            if (key.equals(keyOf(entries.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    private static List<Object> asList(@Nullable Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of(value);
    }

    private static String describe(@Nullable Object value) {
        if (value == null) {
            return "nothing";
        }
        if (value instanceof Map<?, ?> map) {
            return "a mapping with " + map.size() + " keys";
        }
        if (value instanceof List<?> list) {
            return "a list of " + list.size() + " entries";
        }
        return "'" + value + "'";
    }
}
