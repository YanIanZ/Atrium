package dev.yanianz.atrium.module.importer;

import dev.yanianz.atrium.api.teleport.SpawnAdminService;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.ArrayList;
import java.util.List;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Reads the spawn points from CMI's {@code config.yml}. CMI 9 writes locations as one
 * {@code world;x;y;z;yaw;pitch} string, older versions as separate keys:
 * <pre>
 * Spawn:
 *   SpawnOnJoin: false
 *   Main:              # /cmi setspawn
 *     Location: world;-15.5;71.0;24.5;-89.4;12.9
 *     RespawnLocation: true
 *   Groups:            # /cmi setspawn -g:vip (cmi.spawngroup.vip)
 *     vip:
 *       Location: world_nether;10.5;65.0;10.5;180.0;0.0
 *   FirstSpawn:        # /cmi setfirstspawn
 *     Use: true
 *     Location: world;100.5;70.0;-50.5;180.0;0.0    # or World/X/Y/Z/Pitch/Yaw (World: None = not set)
 * Newbie:
 *   Kit: starter     # kit given on the first join
 * </pre>
 * A spawn whose location was never set (empty {@code Location}, {@code World: None}) is not an error: CMI then
 * uses the main spawn, and so does Atrium.
 */
final class CmiSpawnParser {
    private CmiSpawnParser() {}

    /** {@code group} is set for group spawns ({@code /cmi setspawn -g:<group>}). */
    record ParsedSpawn(String label, SpawnAdminService.Kind kind, StoredLocation location, String problem,
            String group) {
        ParsedSpawn(String label, SpawnAdminService.Kind kind, StoredLocation location, String problem) {
            this(label, kind, location, problem, null);
        }

        boolean ok() {
            return problem == null;
        }
    }

    record Result(List<ParsedSpawn> spawns, String firstJoinKit, List<String> notes) {
        Result(List<ParsedSpawn> spawns, String firstJoinKit) {
            this(spawns, firstJoinKit, List.of());
        }
    }

    static Result parse(ConfigurationNode root) {
        if (root == null || root.virtual()) {
            return new Result(List.of(), null);
        }
        List<ParsedSpawn> spawns = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        ConfigurationNode spawn = CmiNodeLocations.child(root, "Spawn", "spawn");
        if (!spawn.virtual()) {
            ConfigurationNode main = CmiNodeLocations.child(spawn, "Main", "main");
            if (!main.virtual()) {
                if (unset(main)) {
                    notes.add("CMI Spawn.Main has no location (never set with /cmi setspawn)");
                } else {
                    spawns.add(read("main spawn", SpawnAdminService.Kind.DEFAULT, main));
                }
            }
            ConfigurationNode groups = CmiNodeLocations.child(spawn, "Groups", "groups");
            if (groups.isMap()) {
                for (var entry : groups.childrenMap().entrySet()) {
                    String group = String.valueOf(entry.getKey());
                    if (unset(entry.getValue())) {
                        continue;
                    }
                    ParsedSpawn parsed = read("group spawn " + group, SpawnAdminService.Kind.DEFAULT, entry.getValue());
                    spawns.add(parsed.ok() ? new ParsedSpawn(parsed.label(), parsed.kind(), parsed.location(), null,
                            group) : parsed);
                }
            }
            ConfigurationNode first = CmiNodeLocations.child(spawn, "FirstSpawn", "firstSpawn", "FirstJoin");
            if (!first.virtual() && CmiNodeLocations.truthy(first, true, "Use", "Enabled")) {
                if (unset(first)) {
                    notes.add("CMI Spawn.FirstSpawn has no location; new players use the main spawn");
                } else {
                    spawns.add(read("first spawn", SpawnAdminService.Kind.FIRST_JOIN, first));
                }
            }
        }
        String kit = CmiNodeLocations.text(CmiNodeLocations.child(root, "Newbie", "newbie"), "Kit", "kit");
        return new Result(List.copyOf(spawns), kit == null || kit.isBlank() ? null : kit.trim(), List.copyOf(notes));
    }

    /** True when the node holds no location at all: an empty Location string and no (or a "None") World. */
    private static boolean unset(ConfigurationNode node) {
        String location = CmiNodeLocations.text(node, "Location", "location", "Loc");
        if (location != null && !location.isBlank()) {
            return false;
        }
        String world = CmiNodeLocations.text(node, "World", "world");
        return world == null || world.isBlank() || world.equalsIgnoreCase("none");
    }

    /** The spawn points to import (see {@link SpawnImport}). */
    static SpawnImport.Plan plan(Result parsed) {
        List<SpawnImport.Spawn> out = new ArrayList<>();
        List<String> notes = new ArrayList<>(parsed.notes());
        boolean groups = false;
        for (ParsedSpawn spawn : parsed.spawns()) {
            if (spawn.ok() && spawn.group() != null) {
                groups = true;
                out.add(SpawnImport.Spawn.group(spawn.label(), spawn.group(), spawn.location()));
            } else {
                out.add(new SpawnImport.Spawn(spawn.label(), spawn.kind(), null, spawn.location(), spawn.problem(), null));
            }
        }
        if (groups) {
            notes.add("CMI group spawns were imported but are ignored while spawn.group-spawns is false (the default: "
                    + "one global main spawn). To use them, enable it in modules/teleport.yml and grant "
                    + "atrium.spawn.group.<group> instead of cmi.spawngroup.<group>.");
        }
        if (out.isEmpty()) {
            notes.add("CMI config.yml has no Spawn.Main or Spawn.FirstSpawn location");
        }
        return new SpawnImport.Plan(out, notes);
    }

    private static ParsedSpawn read(String label, SpawnAdminService.Kind kind, ConfigurationNode node) {
        ConfigurationNode location = CmiNodeLocations.child(node, "Location", "location", "Loc");
        String text = location.virtual() ? null : location.getString();
        var parsed = location.virtual() || (text != null && text.isBlank())
                ? CmiNodeLocations.read(node) : CmiNodeLocations.read(location);
        if (parsed.isEmpty()) {
            return new ParsedSpawn(label, kind, null, label + " has no valid location");
        }
        return new ParsedSpawn(label, kind, parsed.get(), null);
    }
}
