package dev.yanianz.atrium.module.importer;

import dev.yanianz.atrium.api.teleport.SpawnAdminService;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Parses {@code plugins/Essentials/spawn.yml} (written by EssentialsSpawn into the Essentials data folder).
 * EssentialsSpawn stores one spawn per group; the
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

    /**
     * EssentialsSpawn keeps one spawn per permission group. Atrium has a default spawn, a first-join spawn
     * and per-world spawns, so the {@code default} group becomes the default spawn, the group named by
     * {@code newbies.spawnpoint} also becomes the first-join spawn, and other groups are reported.
     */
    static SpawnImport.Plan plan(List<SpawnPoint> points, String newbies, EssentialsLocations.WorldIndex worlds) {
        List<SpawnImport.Spawn> out = new ArrayList<>();
        SpawnPoint preferred = preferred(points);
        for (SpawnPoint point : points) {
            Optional<StoredLocation> resolved = worlds.resolve(point.location());
            String label = "group " + point.group();
            if (resolved.isEmpty()) {
                out.add(SpawnImport.Spawn.invalid(label, SpawnAdminService.Kind.DEFAULT,
                        "spawn is in world '" + point.location().worldToken() + "' which is not loaded"));
                continue;
            }
            boolean main = point == preferred;
            boolean newbieSpawn = newbies != null && !newbies.equalsIgnoreCase("none")
                    && point.group().equalsIgnoreCase(newbies);
            if (main) {
                out.add(SpawnImport.Spawn.ok(label, SpawnAdminService.Kind.DEFAULT, resolved.get()));
            } else if (!newbieSpawn) {
                // The newbies group is used as the first-join spawn below, so it is not reported as dropped.
                out.add(SpawnImport.Spawn.unsupported(label, resolved.get(),
                        "per-group spawn; Atrium has no group spawns (use a per-world spawn or a warp)"));
            }
            if (newbieSpawn) {
                out.add(SpawnImport.Spawn.ok("first-join (newbies.spawnpoint " + newbies + ")",
                        SpawnAdminService.Kind.FIRST_JOIN, resolved.get()));
            }
        }
        return new SpawnImport.Plan(out, List.of());
    }

    /**
     * The folder holding {@code spawn.yml}. EssentialsSpawn writes it into the data folder of Essentials itself
     * ({@code plugins/Essentials/spawn.yml}); a {@code plugins/EssentialsSpawn} copy is accepted as well. A
     * configured folder wins. {@code null} when no spawn.yml exists.
     */
    static Path spawnFolder(Path essentialsFolder, Path configuredFolder) {
        if (configuredFolder != null) {
            return Files.isDirectory(configuredFolder) ? configuredFolder.toAbsolutePath() : null;
        }
        if (Files.isRegularFile(essentialsFolder.resolve("spawn.yml"))) {
            return essentialsFolder.toAbsolutePath();
        }
        Path parent = essentialsFolder.getParent() == null ? essentialsFolder : essentialsFolder.getParent();
        Path legacy = parent.resolve("EssentialsSpawn");
        return Files.isRegularFile(legacy.resolve("spawn.yml")) ? legacy.toAbsolutePath() : null;
    }
}
