package dev.yanianz.atrium.module.mobs;

import java.util.LinkedHashMap;
import java.util.Map;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/mobs.yml */
@ConfigSerializable
public class MobsConfig {
    @Comment("Largest [amount] accepted by /spawnmob (1-500).")
    public int maxAmount = 25;

    @Comment("Most entities in one riding stack, e.g. spider+skeleton is 2 (1-10).")
    public int maxStackDepth = 3;

    @Comment("Most entities one /spawnmob may create in total: amount x stack depth (1-1000).")
    public int maxEntitiesPerCommand = 50;

    @Comment("/spawnmob never fills a chunk beyond this many entities (existing + new, 1-10000).")
    public int maxEntitiesPerChunk = 80;

    @Comment("Largest radius in blocks for /killall (1-512). Players must always give a radius.")
    public int killallMaxRadius = 128;

    @Comment("Radius around the console's location (the main world spawn) when /killall has none (1-512).")
    public int killallConsoleRadius = 64;

    @Comment("How far /mobinfo looks for an entity, in blocks (1-32).")
    public double mobinfoReach = 16;

    @Comment("How far /setspawner and /spawnereditor look for a spawner, in blocks (1-16).")
    public double spawnerReach = 8;

    @Comment("Longest lifetime accepted by the t:<seconds> option of /spawnmob (1-3600).")
    public int maxLifetimeSeconds = 900;

    @Comment("Spawner behaviour: silk-touch drops, charges, egg permissions and the placement guard.")
    public Spawners spawners = new Spawners();

    @Comment("Head drops when a player kills another player or a mob.")
    public Heads heads = new Heads();

    @ConfigSerializable
    public static class Spawners {
        @Comment("Master switch for every spawner listener below. /setspawner and /spawnereditor keep working.")
        public boolean enabled = true;

        @Comment("Breaking a spawner with silk touch keeps the mob type it spawns.")
        public Silk silk = new Silk();

        @Comment("Optional charge system: one spawner break costs a charge that refills over time.")
        public Charges charges = new Charges();

        @Comment("Require a permission before a spawn egg may change a spawner.")
        public Egg egg = new Egg();

        @Comment("Refuse to place a spawner next to another spawner.")
        public Proximity proximity = new Proximity();

        @Comment("The /spawnereditor GUI.")
        public Editor editor = new Editor();

        @ConfigSerializable
        public static class Silk {
            @Comment("Drop exactly one spawner item (with its type) instead of the vanilla drop.")
            public boolean enabled = true;

            @Comment("Only drop the spawner when the tool has silk touch.")
            public boolean requireSilkTouch = true;

            @Comment("Require atrium.spawner.silk (and atrium.spawner.place when placing it back).")
            public boolean requirePermission = true;
        }

        @ConfigSerializable
        public static class Charges {
            @Comment("Enable spending charges to break spawners. Off by default: it changes how spawners behave.")
            public boolean enabled = false;

            @Comment("Charges a player starts with (0-100).")
            public int startingCharges = 1;

            @Comment("Most charges a player can hold (1-100).")
            public int maxCharges = 3;

            @Comment("Seconds until one charge refills (1-604800).")
            public int cooldownSeconds = 3600;

            @Comment("Player chance in percent that a paid break drops the spawner item (0-100).")
            public double dropChance = 100;

            @Comment("Also require silk touch before a paid break can drop the spawner (on by default).")
            public boolean requireSilkTouch = true;

            @Comment("Creative players never spend charges.")
            public boolean creativeBypass = true;

            @Comment("Permission node checked for every spawner break while charges are enabled (empty = none).")
            public String permission = "atrium.spawner.charge";
        }

        @ConfigSerializable
        public static class Egg {
            @Comment("Enable the per-type egg permission. Off by default: it restricts vanilla behaviour.")
            public boolean enabled = false;

            @Comment("Permission prefix; the entity type is appended, e.g. atrium.spawner.egg.zombie.")
            public String permissionPrefix = "atrium.spawner.egg";
        }

        @ConfigSerializable
        public static class Proximity {
            @Comment("Enable the guard that refuses a spawner placed near another spawner.")
            public boolean enabled = false;

            @Comment("Largest distance in blocks between two spawners (1-32). Only loaded chunks are scanned.")
            public int radius = 8;

            @Comment("Bypass the guard with this permission.")
            public String bypassPermission = "atrium.spawner.proximity.bypass";
        }

        @ConfigSerializable
        public static class Editor {
            @Comment("Allow /spawnereditor to open the GUI.")
            public boolean enabled = true;
        }
    }

    @ConfigSerializable
    public static class Heads {
        @Comment("Enable head drops. Off by default: it adds items to mob and player deaths.")
        public boolean enabled = false;

        @Comment("Chance in percent that a player killed by a player drops their head (0-100).")
        public double playerChance = 5;

        @Comment("Extra chance as a fraction of the base per looting level (0.02 = +2 %).")
        public double lootingBonusPerLevel = 0.02;

        @Comment("Require a player kill; when off, mobs killed by the environment may drop heads too.")
        public boolean onlyPlayerKills = true;

        @Comment("Keep the inventory rule still drops a head (false = skip when keepInventory is on).")
        public boolean dropWithKeepInventory = false;

        @Comment("Chance per entity type in percent. Types without a head item are ignored.")
        public Map<String, Double> entityChances = defaultEntityChances();

        private static Map<String, Double> defaultEntityChances() {
            Map<String, Double> chances = new LinkedHashMap<>();
            chances.put("ZOMBIE", 0.5);
            chances.put("HUSK", 0.5);
            chances.put("DROWNED", 0.5);
            chances.put("SKELETON", 0.5);
            chances.put("STRAY", 0.5);
            chances.put("BOGGED", 0.5);
            chances.put("WITHER_SKELETON", 0.5);
            chances.put("CREEPER", 0.5);
            chances.put("PIGLIN", 0.3);
            chances.put("PIGLIN_BRUTE", 0.3);
            chances.put("ENDER_DRAGON", 100.0);
            return chances;
        }
    }

    int maxAmount() {
        return clamp(maxAmount, 1, 500);
    }

    int maxStackDepth() {
        return clamp(maxStackDepth, 1, 10);
    }

    int maxEntitiesPerCommand() {
        return clamp(maxEntitiesPerCommand, 1, 1000);
    }

    int maxEntitiesPerChunk() {
        return clamp(maxEntitiesPerChunk, 1, 10000);
    }

    int killallMaxRadius() {
        return clamp(killallMaxRadius, 1, 512);
    }

    int killallConsoleRadius() {
        return clamp(killallConsoleRadius, 1, killallMaxRadius());
    }

    double mobinfoReach() {
        return Double.isFinite(mobinfoReach) ? Math.max(1, Math.min(32, mobinfoReach)) : 16;
    }

    double spawnerReach() {
        return Double.isFinite(spawnerReach) ? Math.max(1, Math.min(16, spawnerReach)) : 8;
    }

    int maxLifetimeSeconds() {
        return clamp(maxLifetimeSeconds, 1, 3600);
    }

    int proximityRadius() {
        return clamp(spawners.proximity.radius, 1, 32);
    }

    int startingCharges() {
        return clamp(spawners.charges.startingCharges, 0, 100);
    }

    int maxCharges() {
        return Math.max(startingCharges(), clamp(spawners.charges.maxCharges, 1, 100));
    }

    int chargeCooldownSeconds() {
        return clamp(spawners.charges.cooldownSeconds, 1, 604_800);
    }

    double spawnerDropChance() {
        return Double.isFinite(spawners.charges.dropChance)
                ? Math.max(0, Math.min(100, spawners.charges.dropChance)) : 0;
    }

    double playerHeadChance() {
        return Double.isFinite(heads.playerChance) ? Math.max(0, Math.min(100, heads.playerChance)) : 0;
    }

    double lootingBonus() {
        return Double.isFinite(heads.lootingBonusPerLevel)
                ? Math.max(0, Math.min(1, heads.lootingBonusPerLevel)) : 0;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
