package dev.yanianz.atrium.module.teleport;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Decides which spawn point {@code /spawn}, the first join and the respawn use. Pure (no server calls) so the
 * whole decision table is unit tested; the caller turns the chosen {@link StoredLocation} into a world (loading
 * it on demand when needed).
 *
 * <p>By default there is ONE global main spawn: {@code /spawn} and the respawn's {@code spawn} token use it in
 * every world, and stored per-world and group spawns are ignored. Two opt-in options add steps before it:
 * <ol>
 *   <li>per-world spawns on: the spawn of the player's world, when an admin set one there;</li>
 *   <li>group spawns on: the spawn of the first group the player belongs to ({@code atrium.spawn.group.<g>});</li>
 *   <li>the main (default) spawn;</li>
 *   <li>the vanilla spawn of the world (per-world on) or of the first world.</li>
 * </ol>
 * The first join uses the first-join spawn, then the main spawn, then the vanilla spawn.
 */
final class SpawnResolver {
    /** Where a choice came from. */
    enum Source { WORLD, GROUP, DEFAULT, FIRST_JOIN, VANILLA }

    /**
     * @param key      the world (WORLD, VANILLA) or group (GROUP) the choice belongs to; {@code null} otherwise
     * @param location the stored point; {@code null} for {@link Source#VANILLA}
     */
    record Choice(Source source, @Nullable String key, @Nullable StoredLocation location) {
        boolean stored() {
            return location != null;
        }

        /** Short description for admins: {@code world spawn of 'x'}, {@code group spawn 'vip'}, ... */
        String label() {
            return switch (source) {
                case WORLD -> "per-world spawn of '" + key + "'";
                case GROUP -> "group spawn '" + key + "'";
                case DEFAULT -> "main spawn";
                case FIRST_JOIN -> "first-join spawn";
                case VANILLA -> key == null ? "vanilla spawn of the first world" : "vanilla spawn of '" + key + "'";
            };
        }
    }

    static final String GROUP_PERMISSION_PREFIX = "atrium.spawn.group.";

    private SpawnResolver() {}

    static String groupPermission(String group) {
        return GROUP_PERMISSION_PREFIX + group.toLowerCase(Locale.ROOT);
    }

    /** Spawn points of one kind from the store map: world or group name to location, sorted by name. */
    static Map<String, StoredLocation> withPrefix(Map<String, StoredLocation> spawns, String prefix) {
        Map<String, StoredLocation> out = new TreeMap<>();
        for (Map.Entry<String, StoredLocation> entry : spawns.entrySet()) {
            if (entry.getKey().startsWith(prefix) && entry.getKey().length() > prefix.length()) {
                out.put(entry.getKey().substring(prefix.length()), entry.getValue());
            }
        }
        return out;
    }

    /** The groups in the order they are checked: the configured order first, the others alphabetically. */
    static List<String> groupOrder(Collection<String> groups, @Nullable List<String> configured) {
        List<String> out = new ArrayList<>();
        if (configured != null) {
            for (String wanted : configured) {
                if (wanted == null) {
                    continue;
                }
                for (String group : groups) {
                    if (group.equalsIgnoreCase(wanted.trim()) && !out.contains(group)) {
                        out.add(group);
                    }
                }
            }
        }
        groups.stream().sorted(String.CASE_INSENSITIVE_ORDER).filter(group -> !out.contains(group)).forEach(out::add);
        return out;
    }

    /**
     * The spawn of {@code /spawn} (and the respawn's {@code spawn} token) for a player in {@code world}.
     *
     * @param member whether the player belongs to a group (only asked for groups that have a spawn)
     */
    static Choice forPlayer(Map<String, StoredLocation> spawns, boolean perWorld, boolean groupSpawns,
            @Nullable String world, Predicate<String> member, @Nullable List<String> configuredGroupOrder) {
        if (perWorld && world != null) {
            StoredLocation own = spawns.get(TeleportStore.worldSpawnId(world));
            if (own != null) {
                return new Choice(Source.WORLD, world, own);
            }
        }
        if (groupSpawns) {
            Map<String, StoredLocation> groups = withPrefix(spawns, TeleportStore.GROUP_PREFIX);
            for (String group : groupOrder(groups.keySet(), configuredGroupOrder)) {
                if (member.test(group)) {
                    return new Choice(Source.GROUP, group, groups.get(group));
                }
            }
        }
        StoredLocation main = spawns.get(TeleportStore.DEFAULT_SPAWN);
        if (main != null) {
            return new Choice(Source.DEFAULT, null, main);
        }
        return new Choice(Source.VANILLA, perWorld ? world : null, null);
    }

    /** The spawn of a new player (no permissions are known yet at that point). */
    static Choice forFirstJoin(Map<String, StoredLocation> spawns) {
        StoredLocation first = spawns.get(TeleportStore.FIRST_JOIN_SPAWN);
        if (first != null) {
            return new Choice(Source.FIRST_JOIN, null, first);
        }
        StoredLocation main = spawns.get(TeleportStore.DEFAULT_SPAWN);
        if (main != null) {
            return new Choice(Source.DEFAULT, null, main);
        }
        return new Choice(Source.VANILLA, null, null);
    }
}
