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

    @Comment("What the server list (multiplayer screen) shows. The MOTD text itself is stored in\n"
            + "plugins/Atrium/data/performance/motd.yml so that /setmotd can change it without rewriting this file.")
    public ServerList serverList = new ServerList();

    @Comment("Limits used by /viewdistance. The client only accepts 2-32 chunks.")
    public ViewDistance viewDistance = new ViewDistance();

    @Comment("Checks the GitHub releases of the Atrium repository for a newer version. Never downloads anything.")
    public Updates updates = new Updates();

    @Comment("Optional log4j filter that hides noisy console lines and keeps the recent problems for /consolefilter.")
    public ConsoleLog consoleLog = new ConsoleLog();

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

    @ConfigSerializable
    public static class ServerList {
        @Comment("Handle the server-list ping at all. Off leaves the event untouched.")
        public boolean enabled = true;

        @Comment("Remove vanished players from the player sample and from the online count. Off by default:\n"
                + "the vanish module already does this (vanish.yml -> hide-from-server-list) and subtracting twice\n"
                + "would show a too low number.")
        public boolean hideVanished = false;

        @Comment("Number manipulations. An override of -1 means 'not set'.")
        public Counts counts = new Counts();

        @ConfigSerializable
        public static class Counts {
            @Comment("Added to the online count reported to the client (-1000..1000).")
            public int onlineOffset = 0;

            @Comment("Reported online count, replacing the real one (-1 = off, 0..10000).")
            public int onlineOverride = -1;

            @Comment("Added to the maximum player count (-1000..1000).")
            public int maxOffset = 0;

            @Comment("Reported maximum, replacing the real one (-1 = off, 1..10000).")
            public int maxOverride = -1;

            int onlineOffset() {
                return clamp(onlineOffset, -1000, 1000);
            }

            int maxOffset() {
                return clamp(maxOffset, -1000, 1000);
            }

            int onlineOverride() {
                return onlineOverride < 0 ? -1 : clamp(onlineOverride, 0, 10_000);
            }

            int maxOverride() {
                return maxOverride < 0 ? -1 : clamp(maxOverride, 1, 10_000);
            }
        }
    }

    @ConfigSerializable
    public static class ViewDistance {
        @Comment("Lowest distance accepted for a player, a world and the server (clamped to the client's 2-32).")
        public int min = 2;

        @Comment("Highest distance accepted for a player, a world and the server (2-32).")
        public int max = 32;

        int min() {
            return clamp(min, 2, 32);
        }

        int max() {
            return clamp(Math.max(max, min()), 2, 32);
        }
    }

    @ConfigSerializable
    public static class Updates {
        @Comment("Periodically ask GitHub for the newest release. Only the public releases API is called\n"
                + "and the answer is only shown in chat; nothing is ever downloaded or installed.")
        public boolean enabled = true;

        @Comment("Repository checked, as owner/name.")
        public String repository = "YanIanZ/Atrium";

        @Comment("Also check once when the server starts.")
        public boolean checkOnStart = true;

        @Comment("Hours between checks (1-168).")
        public int intervalHours = 6;

        @Comment("Seconds to wait for GitHub before giving up (3-60).")
        public int timeoutSeconds = 10;

        @Comment("Tell players with atrium.updates.notify about a newer release when they join.")
        public boolean notifyOnJoin = true;

        int intervalHours() {
            return clamp(intervalHours, 1, 168);
        }

        int timeoutSeconds() {
            return clamp(timeoutSeconds, 3, 60);
        }
    }

    @ConfigSerializable
    public static class ConsoleLog {
        @Comment("Install the log4j filter. Off by default: hiding console lines surprises existing setups.")
        public boolean enabled = false;

        @Comment("Also hide lines that carry a stack trace. Off is safer: a real failure must stay visible.")
        public boolean hideErrors = false;

        @Comment("Recent problems kept for /consolefilter recent (10-1000).")
        public int recentSize = 100;

        @Comment("Rules are checked in order and the first match wins. 'contains' is a plain substring match\n"
                + "(case-insensitive), 'regex' a Java regular expression. action: hide or tag. A tag prefix is\n"
                + "written in front of the line in /consolefilter recent.")
        public List<ConsoleRule> rules = new ArrayList<>();

        int recentSize() {
            return clamp(recentSize, 10, 1000);
        }
    }

    @ConfigSerializable
    public static class ConsoleRule {
        @Comment("Substring or regular expression to match against the whole log line.")
        public String pattern = "";

        @Comment("contains or regex.")
        public String mode = "contains";

        @Comment("hide or tag.")
        public String action = "hide";

        @Comment("Prefix shown for a 'tag' rule (for example [noisy]).")
        public String tag = "";

        @Comment("Only apply the rule to loggers whose name starts with this. Empty = every logger.")
        public String loggerPrefix = "";
    }
}
