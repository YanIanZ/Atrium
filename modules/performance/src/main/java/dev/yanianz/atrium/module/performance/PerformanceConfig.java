package dev.yanianz.atrium.module.performance;

import java.util.ArrayList;
import java.util.List;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/performance.yml */
@ConfigSerializable
public class PerformanceConfig {
    @Comment("Settings for /lag.")
    public Lag lag = new Lag();

    @Comment("Settings for /groundclean and its optional schedule.")
    public GroundClean groundClean = new GroundClean();

    @Comment("Optional per-chunk entity caps. Disabled by default; every cap of 0 disables that category.")
    public EntityLimits entityLimits = new EntityLimits();

    @ConfigSerializable
    public static class Lag {
        @Comment("Seconds to wait for region and world samples before reporting partial values (1-30).")
        public int timeoutSeconds = 5;

        @Comment("Region lines shown by /lag before the rest is summarised (1-50).")
        public int maxRegionLines = 10;

        @Comment("Online players sampled as region owners. Bounds the work of /lag (1-500).")
        public int maxRegionSamples = 100;

        @Comment("Include per-world entity and loaded-chunk counts.")
        public boolean includeWorldCounts = true;

        int timeoutSeconds() {
            return clamp(timeoutSeconds, 1, 30);
        }

        int maxRegionLines() {
            return clamp(maxRegionLines, 1, 50);
        }

        int maxRegionSamples() {
            return clamp(maxRegionSamples, 1, 500);
        }
    }

    @ConfigSerializable
    public static class GroundClean {
        @Comment("Chunk radius around each online player searched by /groundclean (1-32).")
        public int radiusChunks = 6;

        @Comment("Only remove dropped items and XP orbs at least this many seconds old (1-86400).")
        public int minAgeSeconds = 300;

        @Comment("Countdown before a manual /groundclean run in seconds (0-300). -now skips it.")
        public int manualCountdownSeconds = 10;

        @Comment("Countdown before a scheduled run in seconds (0-600). The 60/30/10/5 warnings are announced when they fit.")
        public int scheduledCountdownSeconds = 60;

        @Comment("Warning marks in seconds. A mark is announced only when it is at or below the run countdown.")
        public List<Integer> warningSeconds = new ArrayList<>(List.of(60, 30, 10, 5));

        @Comment("Radius in blocks around a player holding atrium.groundclean.protect where drops are kept (0-64).")
        public int protectRadius = 8;

        @Comment("Remove dropped item entities.")
        public boolean removeItems = true;

        @Comment("Remove experience orb entities.")
        public boolean removeExperienceOrbs = true;

        @Comment("Item materials that are never removed. Accepts keys like minecraft:diamond, bare names\n"
                + "like diamond, upper-case enum names and * wildcards such as minecraft:*shulker_box.")
        public List<String> whitelistedMaterials = new ArrayList<>();

        @Comment("Custom display names that are never removed. Matching is case-insensitive; * and ? are wildcards.")
        public List<String> whitelistedNames = new ArrayList<>();

        @Comment("Run a cleanup automatically. The interval is measured from the moment the module is enabled.")
        public boolean scheduleEnabled = false;

        @Comment("Minutes between scheduled cleanups (1-10080).")
        public int scheduleIntervalMinutes = 60;

        @Comment("World cleaned by the schedule. Empty means every world that has online players.")
        public String scheduleWorld = "";

        @Comment("Players whose surroundings are inspected per run. A hard bound on work (1-500).")
        public int maxPlayersPerRun = 200;

        @Comment("Seconds to wait for the protection zones before the run is aborted (1-30).")
        public int protectionTimeoutSeconds = 10;

        @Comment("Seconds to wait for region cleanup tasks before reporting partial results (5-120).")
        public int runTimeoutSeconds = 30;

        int radiusChunks() {
            return clamp(radiusChunks, 1, 32);
        }

        int minAgeSeconds() {
            return clamp(minAgeSeconds, 1, 86_400);
        }

        int manualCountdownSeconds() {
            return clamp(manualCountdownSeconds, 0, 300);
        }

        int scheduledCountdownSeconds() {
            return clamp(scheduledCountdownSeconds, 0, 600);
        }

        int protectRadius() {
            return clamp(protectRadius, 0, 64);
        }

        long minAgeTicks() {
            return (long) minAgeSeconds() * 20L;
        }

        int scheduleIntervalMinutes() {
            return clamp(scheduleIntervalMinutes, 1, 10_080);
        }

        int maxPlayersPerRun() {
            return clamp(maxPlayersPerRun, 1, 500);
        }

        int protectionTimeoutSeconds() {
            return clamp(protectionTimeoutSeconds, 1, 30);
        }

        int runTimeoutSeconds() {
            return clamp(runTimeoutSeconds, 5, 120);
        }
    }

    @ConfigSerializable
    public static class EntityLimits {
        @Comment("Enforce the caps below. False leaves spawn events untouched.")
        public boolean enabled = false;

        @Comment("Maximum animals per chunk (0 disables the category).")
        public int animals = 24;

        @Comment("Maximum monsters per chunk (0 disables the category).")
        public int monsters = 24;

        @Comment("Maximum dropped item entities per chunk (0 disables the category).")
        public int items = 120;

        @Comment("Maximum minecarts per chunk (0 disables the category).")
        public int minecarts = 8;

        @Comment("Seconds between rate-limited entity-limit log lines (1-3600).")
        public int logIntervalSeconds = 30;

        int cap(EntityCategory category) {
            if (!enabled) {
                return 0;
            }
            return clamp(switch (category) {
                case ANIMALS -> animals;
                case MONSTERS -> monsters;
                case ITEMS -> items;
                case MINECARTS -> minecarts;
            }, 0, 10_000);
        }

        long logIntervalMillis() {
            return clamp(logIntervalSeconds, 1, 3_600) * 1000L;
        }
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
