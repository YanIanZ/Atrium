package dev.yanianz.atrium.module.rtp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.World;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/rtp.yml */
@ConfigSerializable
public class RtpConfig {
    @Comment("How many random-teleport searches for players may run at the same time on the whole server.\n"
            + "Further /rtp calls are refused with a 'busy' message until one finishes.")
    public int maxConcurrentSearches = 4;

    @Comment("How many searches one player may have running at the same time.")
    public int maxSearchesPerPlayer = 1;

    @Comment("When true, players need atrium.rtp.world.<world> (lower case world name) for every world.\n"
            + "When false, every enabled world is allowed unless that permission is explicitly set to false.")
    public boolean requireWorldPermission = false;

    @Comment("Allow /rtp <world> into another world than the one the player is in (the world permission still applies).")
    public boolean allowOtherWorlds = true;

    @Comment("World used by a plain /rtp when the player's world has random teleport disabled. Empty = refuse.")
    public String fallbackWorld = "";

    @Comment("Give worlds that are not listed under 'worlds' the default settings for their kind\n"
            + "(overworld, nether, end). When false, only listed worlds can be used.")
    public boolean autoWorlds = true;

    @Comment("Ready-made locations, searched in the background, so /rtp can teleport without waiting.")
    public Pool pool = new Pool();

    @Comment("Random teleport new players when they join for the first time (instead of the spawn).")
    public FirstJoin firstJoin = new FirstJoin();

    @Comment("Random teleport players after they respawn.")
    public Respawn respawn = new Respawn();

    @Comment("Per-world settings, by world name. Options left out get their safe default value. Options:\n"
            + "  enabled, center (SPAWN | BORDER | FIXED), center-x, center-z, min-radius, max-radius,\n"
            + "  shape (CIRCLE: ring uniform by area | SQUARE: square ring), max-attempts (points tried per search),\n"
            + "  generate-chunks (false = only already generated terrain), allowed-biomes (empty = all), blocked-biomes,\n"
            + "  avoid-water, avoid-leaves, avoid-blocks (lava is always avoided; the Nether bedrock roof is always\n"
            + "  skipped and the search starts below it), cooldown-seconds (per world, survives restarts),\n"
            + "  cost (economy module; charged once a spot was found, refunded if the teleport fails).\n"
            + "The search area is always cut to the inside of the world border (border centre ± size / 2).")
    public Map<String, WorldSettings> worlds = defaultWorlds();

    private static Map<String, WorldSettings> defaultWorlds() {
        Map<String, WorldSettings> map = new LinkedHashMap<>();
        map.put("world", WorldSettings.forEnvironment(World.Environment.NORMAL));
        map.put("world_nether", WorldSettings.forEnvironment(World.Environment.NETHER));
        map.put("world_the_end", WorldSettings.forEnvironment(World.Environment.THE_END));
        return map;
    }

    /** Settings for the world, or null when random teleport is not available there. */
    @Nullable WorldSettings settings(String world, World.Environment environment) {
        if (worlds != null) {
            for (Map.Entry<String, WorldSettings> entry : worlds.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(world)) {
                    WorldSettings s = entry.getValue();
                    return s != null && s.enabled ? s : null;
                }
            }
        }
        return autoWorlds ? WorldSettings.forEnvironment(environment) : null;
    }

    @ConfigSerializable
    public static class Pool {
        @Comment("Ready locations kept per world. 0 disables the pool (every /rtp searches live).")
        public int size = 3;

        @Comment("Seconds between background refills (one search per world at a time).")
        public int refillIntervalSeconds = 5;

        @Comment("Seconds after server start before the first refill.")
        public int initialDelaySeconds = 30;

        @Comment("Pooled locations older than this many minutes are thrown away (the world may have changed).\n"
                + "Every pooled location is checked again before it is used anyway.")
        public int maxAgeMinutes = 15;
    }

    @ConfigSerializable
    public static class FirstJoin {
        @Comment("Enable the random teleport on first join.")
        public boolean enabled = false;

        @Comment("World to teleport to. Empty = the world the player spawned in.")
        public String world = "";
    }

    @ConfigSerializable
    public static class Respawn {
        @Comment("Enable the random teleport after respawning.")
        public boolean enabled = false;

        @Comment("Only when the player has no bed or respawn anchor.")
        public boolean onlyWithoutBed = true;

        @Comment("World to teleport to. Empty = the world the player respawned in.")
        public String world = "";
    }

    @ConfigSerializable
    public static class WorldSettings {
        @Comment("Allow random teleport in this world.")
        public boolean enabled = true;

        @Comment("Centre of the search area: SPAWN (world spawn), BORDER (world border centre) or FIXED (center-x/center-z).")
        public String center = "SPAWN";

        @Comment("Centre X when center is FIXED.")
        public int centerX = 0;

        @Comment("Centre Z when center is FIXED.")
        public int centerZ = 0;

        @Comment("Smallest distance from the centre, in blocks.")
        public int minRadius = 200;

        @Comment("Largest distance from the centre, in blocks. The area is always cut to the inside of the world border.")
        public int maxRadius = 3000;

        @Comment("CIRCLE (ring between min and max radius, uniform by area) or SQUARE (square ring).")
        public String shape = "CIRCLE";

        @Comment("Random points tried per search before giving up.")
        public int maxAttempts = 12;

        @Comment("Allow generating new chunks while searching. false = only already generated terrain is used.")
        public boolean generateChunks = true;

        @Comment("Biomes allowed as destination (e.g. minecraft:plains). Empty = every biome not blocked below.")
        public List<String> allowedBiomes = new ArrayList<>();

        @Comment("Biomes never used as destination.")
        public List<String> blockedBiomes = new ArrayList<>();

        @Comment("Never land in or on water.")
        public boolean avoidWater = true;

        @Comment("Never land on tree leaves.")
        public boolean avoidLeaves = true;

        @Comment("Blocks never landed on or in (material names). Lava is always avoided. In worlds with a\n"
                + "ceiling (the Nether) the bedrock roof is always skipped and the search starts below it.")
        public List<String> avoidBlocks = new ArrayList<>(List.of("LAVA", "MAGMA_BLOCK", "CACTUS", "POWDER_SNOW",
                "FIRE", "SOUL_FIRE", "CAMPFIRE", "SOUL_CAMPFIRE", "SWEET_BERRY_BUSH", "WITHER_ROSE",
                "POINTED_DRIPSTONE", "COBWEB", "NETHER_PORTAL", "END_PORTAL", "END_GATEWAY"));

        @Comment("Seconds before a player may random teleport in this world again. 0 = no cooldown.\n"
                + "Players with atrium.rtp.cooldown.bypass are never on cooldown. Survives restarts.")
        public int cooldownSeconds = 300;

        @Comment("Price of a random teleport in this world (needs the economy module). 0 = free.\n"
                + "Charged only after a location was found and refunded if the teleport does not happen.")
        public double cost = 0.0;

        static WorldSettings forEnvironment(World.Environment environment) {
            WorldSettings s = new WorldSettings();
            switch (environment) {
                case NETHER -> {
                    s.minRadius = 100;
                    s.maxRadius = 1000;
                    s.maxAttempts = 16;
                }
                case THE_END -> {
                    // The main island ends at about 150 blocks; the outer islands start near 1000.
                    s.center = "FIXED";
                    s.minRadius = 1100;
                    s.maxRadius = 3000;
                    s.maxAttempts = 24;
                    s.avoidBlocks.addAll(List.of("CHORUS_PLANT", "CHORUS_FLOWER"));
                }
                default -> s.blockedBiomes.addAll(List.of("minecraft:ocean", "minecraft:deep_ocean",
                        "minecraft:cold_ocean", "minecraft:deep_cold_ocean", "minecraft:frozen_ocean",
                        "minecraft:deep_frozen_ocean", "minecraft:lukewarm_ocean", "minecraft:deep_lukewarm_ocean",
                        "minecraft:warm_ocean", "minecraft:river", "minecraft:frozen_river"));
            }
            return s;
        }

        Sampler.Shape shapeValue() {
            return "SQUARE".equalsIgnoreCase(shape == null ? "" : shape.trim()) ? Sampler.Shape.SQUARE : Sampler.Shape.CIRCLE;
        }

        String centerMode() {
            return center == null ? "SPAWN" : center.trim().toUpperCase(Locale.ROOT);
        }
    }
}
