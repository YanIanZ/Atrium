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

        @Comment("Respawn players at the spawn when they have no bed or respawn anchor.")
        public boolean respawnAtSpawn = true;

        @Comment("Also move the vanilla world spawn point when /setspawn sets a world spawn (compasses, new chunks).")
        public boolean syncWorldSpawn = true;
    }
}
