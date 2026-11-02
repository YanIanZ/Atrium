package dev.yanianz.atrium.module.importer;

import dev.yanianz.atrium.api.teleport.SpawnAdminService;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.ArrayList;
import java.util.List;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Reads the spawn points from CMI's {@code config.yml}:
 * <pre>
 * Spawn:
 *   Main:            # /cmi setspawn
 *     World: world
 *     X: 0.5
 *     Y: 64.0
 *     Z: 0.5
 *     Pitch: 0.0
 *     Yaw: 90.0
 *   FirstSpawn:      # spawn of players joining for the first time
 *     Use: true
 *     Location: world;0.5;64.0;0.5;90.0;0.0
 * Newbie:
 *   Kit: starter     # kit given on the first join
 * </pre>
 */
final class CmiSpawnParser {
    private CmiSpawnParser() {}

    record ParsedSpawn(String label, SpawnAdminService.Kind kind, StoredLocation location, String problem) {
        boolean ok() {
            return problem == null;
        }
    }

    record Result(List<ParsedSpawn> spawns, String firstJoinKit) {}

    static Result parse(ConfigurationNode root) {
        if (root == null || root.virtual()) {
            return new Result(List.of(), null);
        }
        List<ParsedSpawn> spawns = new ArrayList<>();
        ConfigurationNode spawn = CmiNodeLocations.child(root, "Spawn", "spawn");
        if (!spawn.virtual()) {
            ConfigurationNode main = CmiNodeLocations.child(spawn, "Main", "main");
            if (!main.virtual()) {
                spawns.add(read("main spawn", SpawnAdminService.Kind.DEFAULT, main));
            }
            ConfigurationNode first = CmiNodeLocations.child(spawn, "FirstSpawn", "firstSpawn", "FirstJoin");
            if (!first.virtual() && CmiNodeLocations.truthy(first, true, "Use", "Enabled")) {
                spawns.add(read("first spawn", SpawnAdminService.Kind.FIRST_JOIN, first));
            }
        }
        String kit = CmiNodeLocations.text(CmiNodeLocations.child(root, "Newbie", "newbie"), "Kit", "kit");
        return new Result(List.copyOf(spawns), kit == null || kit.isBlank() ? null : kit.trim());
    }

    private static ParsedSpawn read(String label, SpawnAdminService.Kind kind, ConfigurationNode node) {
        ConfigurationNode location = CmiNodeLocations.child(node, "Location", "location", "Loc");
        var parsed = location.virtual() ? CmiNodeLocations.read(node) : CmiNodeLocations.read(location);
        if (parsed.isEmpty()) {
            return new ParsedSpawn(label, kind, null, label + " has no valid location");
        }
        return new ParsedSpawn(label, kind, parsed.get(), null);
    }
}
