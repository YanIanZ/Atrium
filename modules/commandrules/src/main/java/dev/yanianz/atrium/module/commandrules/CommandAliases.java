package dev.yanianz.atrium.module.commandrules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure alias-&gt;root index for the server command map and the Brigadier command tree.
 *
 * <p>Bukkit's command map only gives aliases for the command that declares them. Paper registers every
 * alias as its own command wrapper with an empty alias list, and vanilla aliases such as {@code /tp} are
 * separate redirect nodes. This class combines three sources, in order of trust:
 *
 * <ol>
 *   <li>Brigadier redirects ({@code /tp -> /teleport}): the redirect target is the real root.</li>
 *   <li>{@code Command#getAliases()} of every known command. The candidate root may be longer than the
 *       alias ({@code bal -> balance}); the old length heuristic was exactly the bug.</li>
 *   <li>Identity in the command map: keys that resolve to the same {@link org.bukkit.command.Command}
 *       instance belong together.</li>
 * </ol>
 *
 * <p>Aliases that are themselves redirect labels are never used as roots, which keeps the graph
 * acyclic for the usual vanilla aliases.
 */
public final class CommandAliases {
    /** One command map entry. {@code identity} is the command object behind {@code label}. */
    public record Known(String label, String name, List<String> aliases, Object identity) {
        public Known {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }

    private final Map<String, String> roots;

    private CommandAliases(Map<String, String> roots) {
        this.roots = roots;
    }

    /**
     * @param known command-map entries, normally one per key of {@code getKnownCommands()}
     * @param redirects Brigadier literal redirects: alias label -&gt; redirect target label
     */
    public static CommandAliases build(Collection<Known> known, Map<String, String> redirects) {
        List<Known> entries = new ArrayList<>();
        for (Known entry : known) {
            if (entry == null) {
                continue;
            }
            List<String> aliases = new ArrayList<>();
            for (String alias : entry.aliases()) {
                String normalized = normalize(alias);
                if (!normalized.isEmpty()) {
                    aliases.add(normalized);
                }
            }
            entries.add(new Known(normalize(entry.label()), normalize(entry.name()), aliases, entry.identity()));
        }
        entries.sort(Comparator.comparing(Known::label));

        Map<String, String> redirectRoots = new HashMap<>();
        for (String label : redirects.keySet()) {
            String normalized = normalize(label);
            String target = redirectTarget(normalized, redirects);
            if (!normalized.isEmpty() && !target.isEmpty() && !target.equals(normalized)) {
                redirectRoots.put(normalized, target);
            }
        }
        Set<String> redirected = redirectRoots.keySet();

        // Identity: several keys pointing at the same command object belong to one command.
        Map<Object, String> primaryName = new LinkedHashMap<>();
        for (Known entry : entries) {
            if (entry.identity() != null && !entry.name().isEmpty()) {
                primaryName.putIfAbsent(entry.identity(), entry.name());
            }
        }

        Map<String, String> map = new HashMap<>();
        // 1. Declared aliases win over the alias-copy's own name.
        for (Known entry : entries) {
            String root = entry.name();
            if (root.isEmpty() || redirected.contains(root)) {
                continue;
            }
            for (String alias : entry.aliases()) {
                if (!alias.equals(root)) {
                    map.put(alias, root);
                }
            }
        }
        // 2. Every key resolves to its command's name, or to the shared identity name.
        for (Known entry : entries) {
            if (entry.label().isEmpty()) {
                continue;
            }
            String name = entry.identity() == null ? entry.name() : primaryName.get(entry.identity());
            if (name == null || name.isEmpty()) {
                name = entry.name().isEmpty() ? entry.label() : entry.name();
            }
            map.putIfAbsent(entry.label(), name);
        }
        // 3. Redirects are authoritative: they are the server's own alias declaration.
        map.putAll(redirectRoots);
        return new CommandAliases(Map.copyOf(map));
    }

    private static String redirectTarget(String label, Map<String, String> redirects) {
        String current = label;
        Set<String> seen = new HashSet<>();
        while (seen.add(current)) {
            String next = redirects.get(current);
            if (next == null) {
                return current;
            }
            current = normalize(next);
        }
        return "";
    }

    /** The canonical root for a typed label, or the label itself when it is unknown. */
    public String resolve(String label) {
        String current = normalize(label);
        Set<String> seen = new HashSet<>();
        while (seen.add(current)) {
            String next = roots.get(current);
            if (next == null || next.isEmpty() || next.equals(current)) {
                return current;
            }
            current = next;
        }
        return current;
    }

    public boolean isEmpty() {
        return roots.isEmpty();
    }

    private static String normalize(String label) {
        return label == null ? "" : CommandLabels.stripNamespace(label.strip().toLowerCase(Locale.ROOT));
    }
}
