package dev.yanianz.atrium.module.importer;

import java.util.ArrayList;
import java.util.List;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Parses {@code plugins/EssentialsSpawn/spawn.yml}. EssentialsSpawn stores one spawn per group; the
 * {@code default} group is the relevant one, otherwise the first group is reported.
 */
final class EssentialsSpawnParser {
    private EssentialsSpawnParser() {}

    record SpawnPoint(String group, EssentialsLocations.EssLocation location) {}

    static List<SpawnPoint> parse(ConfigurationNode root) {
        if (root == null || root.virtual()) {
            return List.of();
        }
        ConfigurationNode spawns = CmiNodeLocations.child(root, "spawns", "Spawns");
        List<SpawnPoint> points = new ArrayList<>();
        if (!spawns.virtual() && spawns.isMap()) {
            for (var entry : spawns.childrenMap().entrySet()) {
                EssentialsLocations.parse(entry.getValue())
                        .ifPresent(location -> points.add(new SpawnPoint(String.valueOf(entry.getKey()), location)));
            }
            return List.copyOf(points);
        }
        // Older EssentialsSpawn versions wrote the location at the root.
        EssentialsLocations.parse(root).ifPresent(location -> points.add(new SpawnPoint("default", location)));
        return List.copyOf(points);
    }

    /** The group an admin means by "/spawn": {@code default} when present, else the first one. */
    static SpawnPoint preferred(List<SpawnPoint> points) {
        for (SpawnPoint point : points) {
            if (point.group() != null && point.group().equalsIgnoreCase("default")) {
                return point;
            }
        }
        return points.isEmpty() ? null : points.getFirst();
    }
}
