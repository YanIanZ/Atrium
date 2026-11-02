package dev.yanianz.atrium.module.teleport;

import dev.yanianz.atrium.api.teleport.TeleportService.TeleportCause;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/teleport.yml */
@ConfigSerializable
public class TeleportConfig {
    @Comment("Warmup before a teleport starts, in seconds, per cause. Players with atrium.teleport.warmup.bypass skip it.\n"
            + "Causes: COMMAND (/tp, /top), HOME, WARP, SPAWN, RANDOM, REQUEST (/tpa), BACK, DEATH_BACK, PLUGIN.")
    public Map<String, Integer> warmupSeconds = defaults(3, Map.of("COMMAND", 0, "PLUGIN", 0));

    @Comment("Cooldown after a successful teleport, in seconds, per cause (same causes as above).\n"
            + "Players with atrium.teleport.cooldown.bypass are never on cooldown. 0 = no cooldown.")
    public Map<String, Integer> cooldownSeconds = defaults(0, Map.of());

    @Comment("Cancel the warmup when the player moves to another block.")
    public boolean cancelWarmupOnMove = true;

    @Comment("Cancel the warmup when the player takes damage.")
    public boolean cancelWarmupOnDamage = true;

    @Comment("Show the warmup countdown in the action bar every second.")
    public boolean warmupActionBar = true;

    @Comment("Seconds of damage immunity after a successful teleport. 0 = none. Never given for a teleport that failed.")
    public int invulnerabilitySeconds = 3;

    @Comment("Safe landing: move the destination to the nearest safe block in the same column (no lava, void,\n"
            + "fire or solid blocks around the player).")
    public SafeLanding safeLanding = new SafeLanding();

    @Comment("/tpa and /tpahere requests.")
    public Requests requests = new Requests();

    @Comment("/back.")
    public Back back = new Back();

    @Comment("/spawn, first-join spawn and respawn.")
    public Spawn spawn = new Spawn();

    @Comment("Sign elevator: click an indicator block to rise to the next one (sneak-click to sink).")
    public Elevator elevator = new Elevator();

    @Comment("Launchpads: pressure plates that throw a player up and protect them from fall damage.")
    public Launchpads launchpads = new Launchpads();

    @Comment("/jump: leap to the block you are looking at.")
    public Jump jump = new Jump();

    @Comment("Move a player to the nearest safe spot when they join inside blocks.")
    public SafeLogin safeLogin = new SafeLogin();

    @Comment("Carry a ridden vehicle through a teleport (dismount, move, remount).")
    public Vehicle vehicle = new Vehicle();

    private static Map<String, Integer> defaults(int value, Map<String, Integer> overrides) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (TeleportCause cause : TeleportCause.values()) {
            map.put(cause.name(), overrides.getOrDefault(cause.name(), value));
        }
        return map;
    }

    int warmup(TeleportCause cause) {
        return Math.max(0, lookup(warmupSeconds, cause));
    }

    int cooldown(TeleportCause cause) {
        return Math.max(0, lookup(cooldownSeconds, cause));
    }

    private static int lookup(Map<String, Integer> map, TeleportCause cause) {
        if (map == null) {
            return 0;
        }
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (e.getKey() != null && e.getKey().trim().toUpperCase(Locale.ROOT).equals(cause.name())) {
                return e.getValue() == null ? 0 : e.getValue();
            }
        }
        return 0;
    }

    @ConfigSerializable
    public static class SafeLanding {
        @Comment("Causes whose destination is checked for safety. Others teleport to the exact spot.\n"
                + "COMMAND is left out by default so staff /tp lands exactly where asked.")
        public List<String> causes = new ArrayList<>(List.of("HOME", "WARP", "SPAWN", "RANDOM", "REQUEST", "BACK", "DEATH_BACK", "PLUGIN"));

        @Comment("How many blocks above the destination to search for a safe spot.")
        public int searchUp = 12;

        @Comment("How many blocks below the destination to search for a safe spot (more = more void protection).")
        public int searchDown = 24;

        @Comment("Accept water as floor and around the player.")
        public boolean allowWater = true;

        @Comment("Allow landing above the logical height of a world (the Nether roof).")
        public boolean allowNetherRoof = false;

        @Comment("When the destination column has no safe spot, also search the columns around it, nearest first\n"
                + "(a small spiral), up to this many blocks away. 0 = only the destination column. Maximum 8.")
        public int searchRadius = 2;

        @Comment("After a teleport failed because the destination was unsafe, /tpbypass may force that exact spot for\n"
                + "this many seconds (players also need atrium.tpbypass). 0 = /tpbypass is disabled.")
        public int bypassWindowSeconds = 15;

        boolean appliesTo(TeleportCause cause) {
            return causes != null && causes.stream().anyMatch(c -> c != null && c.trim().equalsIgnoreCase(cause.name()));
        }
    }

    @ConfigSerializable
    public static class Requests {
        @Comment("Seconds before a /tpa or /tpahere request expires.")
        public int expireSeconds = 60;

        @Comment("Allow requests between players in different worlds.")
        public boolean allowCrossWorld = true;

        @Comment("Longest distance (in blocks) between the two players of a /tpa. 0 = no limit.\n"
                + "A limit can be raised per player with atrium.tpa.max.<n> or removed with atrium.tpa.max.unlimited.")
        public int maxDistance = 0;

        @Comment("Items that may not be held while sending a request (material names, e.g. ENDER_PEARL).\n"
                + "Useful against exploits that hand an item over right after the teleport.")
        public List<String> blockedMaterials = new ArrayList<>();

        @Comment("How many players one player may block with /tpablock (1-200).")
        public int maxBlocked = 200;
    }

    @ConfigSerializable
    public static class Back {
        @Comment("Only remember a /back location when the teleport moved the player at least this many blocks\n"
                + "(always when the world changes).")
        public double minDistance = 3.0;

        @Comment("Bukkit teleport causes that never update /back (vanilla movement like pearls).")
        public List<String> ignoredBukkitCauses = new ArrayList<>(List.of("ENDER_PEARL", "CHORUS_FRUIT", "CONSUMABLE_EFFECT",
                "DISMOUNT", "EXIT_BED", "SPECTATE", "UNKNOWN"));

        @Comment("Worlds in which no /back location is recorded.")
        public List<String> blacklistedWorlds = new ArrayList<>();

        @Comment("Forget /back locations after this many minutes. 0 = never.")
        public int expireMinutes = 0;

        @Comment("How many previous locations are remembered per player (/back 1 = newest, /back 2 = the one before, ...).\n"
                + "1 behaves like a single /back slot. Maximum 50.")
        public int historySize = 5;

        @Comment("Record the death location for /back (players also need atrium.back.ondeath).")
        public boolean recordDeath = true;

        @Comment("Give atrium.back.ondeath to every player by default (false = operators only).\n"
                + "Takes effect when the module is enabled (server start); a permission plugin can still override it.")
        public boolean onDeathForEveryone = true;
    }

    @ConfigSerializable
    public static class Spawn {
        @Comment("Use a separate spawn per world (/setspawn in that world). Worlds without one use the default spawn.")
        public boolean perWorld = true;

        @Comment("Send new players to the first-join spawn (or the default spawn when no first-join spawn is set).")
        public boolean teleportOnFirstJoin = true;

        @Comment("Choose the respawn point with the order below instead of leaving it to the server.")
        public boolean respawnAtSpawn = true;

        @Comment("Respawn order per world (world name, or \"*\" for every world without its own entry).\n"
                + "Tokens: anchor (respawn anchor), bed, spawn (a spawn set with /setspawn), home, warp:<name>,\n"
                + "worldspawn (the vanilla world spawn). The first token that is available wins.")
        public Map<String, List<String>> respawnOrder = new LinkedHashMap<>(Map.of("*",
                List.of("anchor", "bed", "spawn", "home", "worldspawn")));

        @Comment("Also move the vanilla world spawn point when /setspawn sets a world spawn (compasses, new chunks).")
        public boolean syncWorldSpawn = true;
    }

    @ConfigSerializable
    public static class Elevator {
        @Comment("Enable the sign elevator. Off by default: every click on a matching block would teleport.")
        public boolean enabled = false;

        @Comment("Blocks that work as elevator stops: material names; a trailing '*' matches a prefix\n"
                + "(e.g. \"*_SIGN\" for every sign, \"LODESTONE\" for one material).")
        public List<String> blocks = new ArrayList<>(List.of("*_SIGN"));

        @Comment("Only these worlds have elevators (empty = every world).")
        public List<String> worlds = new ArrayList<>();

        @Comment("Blocks skipped between two stops, so the destination is never the block you stand on.")
        public int minGap = 2;

        @Comment("How far up and down a click searches for the next stop.")
        public int searchRange = 128;

        @Comment("Milliseconds between two elevator rides of one player (spam protection).")
        public long cooldownMillis = 500;
    }

    @ConfigSerializable
    public static class Launchpads {
        @Comment("Enable launchpads. Off by default; configure the plate types below, then turn this on.")
        public boolean enabled = false;

        @Comment("Vertical launch per block type (material name -> upward velocity).\n"
                + "A value around 1.2 lifts a player roughly two blocks, 2.0 roughly six.")
        public Map<String, Double> power = new LinkedHashMap<>(Map.of(
                "LIGHT_WEIGHTED_PRESSURE_PLATE", 1.2,
                "HEAVY_WEIGHTED_PRESSURE_PLATE", 0.8,
                "STONE_PRESSURE_PLATE", 1.5));

        @Comment("Forward push in the direction the player is looking, as a share of the vertical power (0 = none).")
        public double forwardShare = 0.0;

        @Comment("Seconds without fall damage after a launch.")
        public int fallImmunitySeconds = 15;

        @Comment("Milliseconds between two launches of one player (spam protection).")
        public long cooldownMillis = 500;

        @Comment("Only these worlds have launchpads (empty = every world).")
        public List<String> worlds = new ArrayList<>();
    }

    @ConfigSerializable
    public static class Jump {
        @Comment("Furthest block /jump looks at, in blocks. Players raise it with atrium.jump.distance.<n>.")
        public int maxDistance = 50;
    }

    @ConfigSerializable
    public static class SafeLogin {
        @Comment("Relocate a player who joins inside blocks to the nearest safe spot (stops suffocation on login).")
        public boolean enabled = true;

        @Comment("Blocks searched up and down from the join location.")
        public int searchUp = 6;

        @Comment("Blocks searched down from the join location.")
        public int searchDown = 6;

        @Comment("Columns searched to the sides when the join column has no safe spot. Maximum 8.")
        public int searchRadius = 2;
    }

    @ConfigSerializable
    public static class Vehicle {
        @Comment("Carry a ridden vehicle through a teleport: dismount, move the player, move the vehicle and remount.\n"
                + "Off by default. Players also need atrium.teleport.vehicle.")
        public boolean enabled = false;

        @Comment("Vehicle types that are carried (entity type names). Empty = every rideable entity.")
        public List<String> types = new ArrayList<>(List.of("HORSE", "DONKEY", "MULE", "CAMEL", "LLAMA",
                "TRADER_LLAMA", "SKELETON_HORSE", "ZOMBIE_HORSE", "PIG", "STRIDER"));

        boolean allows(String entityType) {
            if (types == null || types.isEmpty()) {
                return true;
            }
            return types.stream().anyMatch(type -> type != null && type.trim().equalsIgnoreCase(entityType));
        }
    }
}
