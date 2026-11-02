package dev.yanianz.atrium.module.importer;

import dev.yanianz.atrium.api.teleport.SpawnAdminService;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Parses {@code plugins/Essentials/spawn.yml} (EssentialsSpawn writes it into the Essentials data folder).
 * EssentialsSpawn keeps one spawn per permission group; {@code /setspawn} without a group writes {@code default}.
 * <pre>
 * spawns:                      # EssentialsX 2.19+ (Configurate)
 *   default:
 *     world: 4d3c1b2a-...      # world UUID (older 2.x files: the world name)
 *     world-name: world        # newer files only
 *     x: 12.5
 *     y: 72.0
 *     z: -340.5
 *     yaw: -90.0
 *     pitch: 12.5
 * </pre>
 * Files from before the {@code spawns:} section keep the groups at the root ({@code default: {world: world, ...}}).
 */
final class EssentialsSpawnParser {
    private EssentialsSpawnParser() {}

    /** The group EssentialsSpawn uses for {@code /setspawn} without a group and for players without one. */
    static final String DEFAULT_GROUP = "default";

    record SpawnPoint(String group, EssentialsLocations.EssLocation location) {}

    static List<SpawnPoint> parse(ConfigurationNode root) {
        if (root == null || root.virtual()) {
            return List.of();
        }
        ConfigurationNode spawns = CmiNodeLocations.child(root, "spawns", "Spawns");
        if (!spawns.virtual() && spawns.isMap()) {
            return groups(spawns);
        }
        // A single location at the root (hand-written files).
        if (!CmiNodeLocations.child(root, "world", "World").virtual()) {
            List<SpawnPoint> single = new ArrayList<>();
            EssentialsLocations.parse(root).ifPresent(location -> single.add(new SpawnPoint(DEFAULT_GROUP, location)));
            return List.copyOf(single);
        }
        // Old EssentialsSpawn: one section per group at the root (what EssentialsUpgrade moves under spawns:).
        return groups(root);
    }

    private static List<SpawnPoint> groups(ConfigurationNode section) {
        List<SpawnPoint> points = new ArrayList<>();
        for (var entry : section.childrenMap().entrySet()) {
            if (!entry.getValue().isMap() && !entry.getValue().isList()) {
                continue;
            }
            // EssentialsSpawn lower-cases group names when it reads the file.
            String group = String.valueOf(entry.getKey()).trim().toLowerCase(Locale.ENGLISH);
            EssentialsLocations.parse(entry.getValue())
                    .ifPresent(location -> points.add(new SpawnPoint(group, location)));
        }
        return List.copyOf(points);
    }

    /** The {@code default} group's spawn, or {@code null} (EssentialsSpawn then uses the world spawn). */
    static SpawnPoint preferred(List<SpawnPoint> points) {
        for (SpawnPoint point : points) {
            if (point.group() != null && point.group().equalsIgnoreCase(DEFAULT_GROUP)) {
                return point;
            }
        }
        return null;
    }

    /**
     * Maps EssentialsSpawn's groups onto Atrium's spawns, keeping what {@code /spawn} did on the old server:
     * <ul>
     *   <li>{@code default} becomes the main spawn (every player without a group spawn, in every world);</li>
     *   <li>the group named by {@code newbies.spawnpoint} becomes the first-join spawn (when it is
     *       {@code default} or missing, new players use the main spawn, as in Essentials);</li>
     *   <li>every other group becomes a group spawn, used for players with {@code atrium.spawn.group.<group>}.</li>
     * </ul>
     */
    static SpawnImport.Plan plan(List<SpawnPoint> points, String newbies, EssentialsLocations.WorldIndex worlds) {
        List<SpawnImport.Spawn> out = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        String newbieGroup = newbies == null || newbies.isBlank() ? DEFAULT_GROUP : newbies.trim().toLowerCase(Locale.ENGLISH);
        for (SpawnPoint point : points) {
            String label = "group " + point.group();
            Optional<StoredLocation> resolved = worlds.resolve(point.location());
            boolean main = point.group().equals(DEFAULT_GROUP);
            boolean newbieSpawn = !main && point.group().equals(newbieGroup);
            if (resolved.isEmpty()) {
                out.add(SpawnImport.Spawn.invalid(label, main ? SpawnAdminService.Kind.DEFAULT
                                : newbieSpawn ? SpawnAdminService.Kind.FIRST_JOIN : SpawnAdminService.Kind.DEFAULT,
                        "spawn is in world '" + point.location().worldToken() + "' which is not loaded and has no name"));
                continue;
            }
            if (main) {
                out.add(SpawnImport.Spawn.ok(label + " (main spawn)", SpawnAdminService.Kind.DEFAULT, resolved.get()));
            } else if (newbieSpawn) {
                out.add(SpawnImport.Spawn.ok("first-join (newbies.spawnpoint " + point.group() + ")",
                        SpawnAdminService.Kind.FIRST_JOIN, resolved.get()));
            } else {
                out.add(SpawnImport.Spawn.group(label, point.group(), resolved.get()));
            }
        }
        if (preferred(points) == null && !points.isEmpty()) {
            notes.add("spawn.yml has no 'default' spawn: EssentialsSpawn sent players without a group spawn to the "
                    + "world spawn, and so does Atrium. Set the main spawn with /setspawn.");
        }
        if (points.stream().anyMatch(p -> !p.group().equals(DEFAULT_GROUP) && !p.group().equals(newbieGroup))) {
            notes.add("Group spawns were imported but Atrium uses ONE global main spawn by default, so they are ignored. "
                    + "To use them like EssentialsSpawn did (by permission group): set spawn.group-spawns: true in "
                    + "modules/teleport.yml and grant atrium.spawn.group.<group> to each group's members (LuckPerms: "
                    + "/lp group <group> permission set atrium.spawn.group.<group>).");
        }
        if (newbies != null && newbies.trim().equalsIgnoreCase("none")) {
            notes.add("Essentials newbies.spawnpoint is 'none' (new players start at the world spawn). Atrium sends new "
                    + "players to the first-join or main spawn unless spawn.teleport-on-first-join is false in "
                    + "modules/teleport.yml.");
        }
        return new SpawnImport.Plan(out, notes);
    }

    /**
     * The folder holding {@code spawn.yml}. EssentialsSpawn writes it into the data folder of Essentials itself
     * ({@code plugins/Essentials/spawn.yml}); a {@code plugins/EssentialsSpawn} copy is accepted as well. A
     * configured folder wins when it contains spawn.yml. {@code null} when no spawn.yml exists.
     */
    static Path spawnFolder(Path essentialsFolder, Path configuredFolder) {
        if (configuredFolder != null && Files.isRegularFile(configuredFolder.resolve("spawn.yml"))) {
            return configuredFolder.toAbsolutePath();
        }
        if (Files.isRegularFile(essentialsFolder.resolve("spawn.yml"))) {
            return essentialsFolder.toAbsolutePath();
        }
        Path parent = essentialsFolder.getParent() == null ? essentialsFolder : essentialsFolder.getParent();
        Path legacy = parent.resolve("EssentialsSpawn");
        return Files.isRegularFile(legacy.resolve("spawn.yml")) ? legacy.toAbsolutePath() : null;
    }
}
