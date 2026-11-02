package dev.yanianz.atrium.module.bossbar;

import dev.yanianz.atrium.module.display.AnimationConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/bossbar.yml */
@ConfigSerializable
public class BossbarConfig {
    @Comment("Ticks between boss bar updates (title, progress, expiry), 2 through 200. 20 ticks = 1 second.")
    public int updateIntervalTicks = 10;

    @Comment("Rotate the announcements below. Players can hide them with /bossbar toggle.")
    public boolean announcementsEnabled = true;

    @Comment("""
            Seconds from the start of one announcement to the start of the next, 1 through 86400. An announcement is
            visible for its own 'seconds' (at most this interval).""")
    public int intervalSeconds = 60;

    @Comment("Pick announcements in a shuffled (but reproducible) order instead of top to bottom.")
    public boolean randomOrder = false;

    @Comment("""
            Announcements. text: MiniMessage (trusted admin text) with <player> <displayname> <online> <max> <world>
            <ping> <tps> <balance> <playtime> <rank>, <anim:name> and %placeholderapi% tokens.
            seconds: how long it is shown. color: pink, blue, red, green, yellow, purple or white.
            overlay: progress, notched_6, notched_10, notched_12 or notched_20.
            progress-mode: countdown (bar empties while shown) or static (bar stays at 'progress', 0.0-1.0).
            worlds: world names (empty = any). permission: required permission (empty = everyone).""")
    public List<Announcement> announcements = new ArrayList<>(List.of(
            Announcement.of("<gray>Welcome to the server, <white><displayname></white>!", 15, "blue", "countdown"),
            Announcement.of("<anim:tip>", 15, "green", "static")));

    @Comment("Animations usable as <anim:name> (frames + interval-ticks or frame-ticks).")
    public Map<String, AnimationConfig> animations = new LinkedHashMap<>(Map.of(
            "tip", AnimationConfig.of(20, List.of(),
                    "<green>Tip:</green> <gray>use <white>/bossbar toggle</white> to hide these messages",
                    "<green>Tip:</green> <gray>there are <white><online></white> players online")));

    @Comment("Color of bars sent with /bossbarmsg.")
    public String messageColor = "yellow";

    @Comment("Overlay of bars sent with /bossbarmsg.")
    public String messageOverlay = "progress";

    @Comment("Longest duration /bossbarmsg accepts, in seconds (1 through 86400).")
    public int maxMessageSeconds = 3600;

    @Comment("Longest /bossbarmsg text in characters (1 through 1024).")
    public int maxMessageLength = 256;

    @Comment("Most /bossbarmsg bars one player sees at once (1 through 10); the oldest is removed first.")
    public int maxMessageBarsPerPlayer = 3;

    @Comment("Resolve %placeholderapi% tokens in announcements when PlaceholderAPI is installed.")
    public boolean placeholderApi = true;

    @Comment("Text shown for <balance>, <playtime>, <rank> or <tps> when the providing module is missing or still loading.")
    public String missingValue = "-";

    @Comment("Seconds between background refreshes of <rank>, <playtime> and uncached <balance> values, 5 through 3600.")
    public int asyncRefreshSeconds = 30;

    @ConfigSerializable
    public static class Announcement {
        @Comment("Bar title.")
        public String text = "";
        @Comment("Seconds shown, at least 1.")
        public int seconds = 10;
        @Comment("Bar color.")
        public String color = "blue";
        @Comment("Bar overlay.")
        public String overlay = "progress";
        @Comment("countdown or static.")
        public String progressMode = "countdown";
        @Comment("Bar fill for static mode, 0.0 through 1.0.")
        public double progress = 1.0;
        @Comment("World names (case-insensitive). Empty = any world.")
        public List<String> worlds = new ArrayList<>();
        @Comment("Permission required to see it. Empty = everyone.")
        public String permission = "";

        static Announcement of(String text, int seconds, String color, String mode) {
            Announcement a = new Announcement();
            a.text = text;
            a.seconds = seconds;
            a.color = color;
            a.progressMode = mode;
            return a;
        }
    }
}
