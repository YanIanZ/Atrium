package dev.yanianz.atrium.module.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/chat.yml */
@ConfigSerializable
public class ChatConfig {
    @Comment("Chat formats, first entry whose permission the sender has wins. Empty permission means everyone. "
            + "Placeholders: <player>, <display_name>, <world>, <message>.")
    public List<FormatEntry> formats = new ArrayList<>(List.of(
            entry("atrium.chat.format.staff", "<dark_red>[STAFF] <white><player><dark_gray> » <white><message>"),
            entry("", "<gray><player><dark_gray> » <white><message>")));

    @Comment("Per-player default chat colour picked with /chatcolor. Only colours listed here can be used.")
    public ChatColors chatColors = new ChatColors();

    @Comment(":shortcode: replacement in player messages. Keys are names without colons.")
    public Emoji emoji = new Emoji();

    @Comment("Cross-server chat through the proxy module. Each channel can be enabled separately;\n"
            + "nothing is sent while the proxy has not reported a server name.")
    public CrossServer crossServer = new CrossServer();

    @Comment("Per-player rate limit and duplicate message detection.")
    public AntiSpam antiSpam = new AntiSpam();

    @Comment("Rules are matched against normalised plain text, never against raw MiniMessage or colour codes.")
    public Filter filter = new Filter();

    @Comment("Highlight @name (or plain names) and play a sound for the mentioned player.")
    public Mentions mentions = new Mentions();

    @Comment("Number of blank lines sent by /clearchat.")
    public int clearChatLines = 100;

    @ConfigSerializable
    public static class FormatEntry {
        @Comment("Permission required for this format; empty means every player.")
        public String permission = "";

        @Comment("Trusted MiniMessage format. Placeholders: <player>, <display_name>, <world>, <message>.")
        public String format = "<gray><player><dark_gray> » <white><message>";

        public FormatEntry() {}

        FormatEntry(String permission, String format) {
            this.permission = permission;
            this.format = format;
        }
    }

    @ConfigSerializable
    public static class AntiSpam {
        @Comment("Enable the rate limit and duplicate message check.")
        public boolean enabled = true;

        @Comment("Minimum milliseconds between two accepted messages. 0 disables the rate limit.")
        public long minimumIntervalMillis = 750L;

        @Comment("Messages identical after normalisation within this many seconds are rejected. 0 disables.")
        public long duplicateWindowSeconds = 10L;
    }

    @ConfigSerializable
    public static class Filter {
        @Comment("Enable chat filter rules.")
        public boolean enabled = true;

        @Comment("Text used to replace a CENSOR match.")
        public String replacement = "***";

        @Comment("Rules have id, type (WORD or REGEX), pattern and action (CENSOR, BLOCK or WARN).")
        public List<FilterRule> rules = new ArrayList<>(List.of(
                rule("sample-word", "WORD", "badword", "CENSOR")));
    }

    @ConfigSerializable
    public static class FilterRule {
        @Comment("Name used in staff alerts.")
        public String id = "rule";

        @Comment("WORD matches the normalised literal word; REGEX matches Java regular expressions.")
        public String type = "WORD";

        @Comment("Word or regular expression to look for.")
        public String pattern = "";

        @Comment("CENSOR hides the match, BLOCK rejects the message, WARN alerts staff but allows it.")
        public String action = "CENSOR";

        public FilterRule() {}

        FilterRule(String id, String type, String pattern, String action) {
            this.id = id;
            this.type = type;
            this.pattern = pattern;
            this.action = action;
        }
    }

    @ConfigSerializable
    public static class Mentions {
        @Comment("Enable mention highlighting and sounds.")
        public boolean enabled = true;

        @Comment("Only treat @name as a mention, not a plain player name.")
        public boolean requireAtSign = false;

        @Comment("Adventure sound key played to the mentioned player. Empty disables the sound.")
        public String sound = "minecraft:entity.experience_orb.pickup";

        @Comment("Sound volume.")
        public float volume = 1.0f;

        @Comment("Sound pitch.")
        public float pitch = 1.0f;
    }

    @ConfigSerializable
    public static class ChatColors {
        @Comment("Master switch for /chatcolor. Per-colour permissions are atrium.chatcolor.<name>;\n"
                + "gradients additionally require atrium.chatcolor.gradient.")
        public boolean enabled = true;

        @Comment("Allowed colours. type is COLOR (value is a named colour or #rrggbb) or GRADIENT\n"
                + "(colors is a list of named colours or #rrggbb stops).")
        public List<ColorEntry> entries = new ArrayList<>(List.of(
                color("red", "red"),
                color("gold", "gold"),
                color("yellow", "yellow"),
                color("green", "green"),
                color("aqua", "aqua"),
                color("blue", "blue"),
                color("pink", "light_purple"),
                color("white", "white"),
                gradient("rainbow", List.of("#ff5555", "#ffaa00", "#ffff55", "#55ff55",
                        "#55ffff", "#5555ff", "#ff55ff")),
                gradient("sunset", List.of("#ff7e5f", "#feb47b"))));
    }

    @ConfigSerializable
    public static class ColorEntry {
        @Comment("Name used by /chatcolor and in atrium.chatcolor.<name>.")
        public String name = "red";

        @Comment("COLOR applies one colour; GRADIENT spreads the colors list over the message.")
        public String type = "COLOR";

        @Comment("Colour for type COLOR: a named colour or #rrggbb.")
        public String value = "red";

        @Comment("Gradient stops for type GRADIENT: named colours or #rrggbb.")
        public List<String> colors = new ArrayList<>();

        public ColorEntry() {}

        ColorEntry(String name, String type, String value, List<String> colors) {
            this.name = name;
            this.type = type;
            this.value = value;
            this.colors = new ArrayList<>(colors);
        }
    }

    @ConfigSerializable
    public static class Emoji {
        @Comment("Enable :shortcode: replacement in chat, /me, /msg and staff chat.")
        public boolean enabled = true;

        @Comment("Shortcode name (without colons) to replacement text. Player text is replaced literally and\n"
                + "URLs are never touched.")
        public Map<String, String> codes = defaultEmoji();
    }

    @ConfigSerializable
    public static class CrossServer {
        @Comment("Master switch for cross-server chat. Requires the proxy module and a configured network.")
        public boolean enabled = true;

        @Comment("Relay public chat to other servers.")
        public boolean globalChat = true;

        @Comment("Relay staff chat to other servers.")
        public boolean staffChat = true;

        @Comment("Allow /msg and /reply to players on another server using the cached network player list.")
        public boolean privateMessages = true;
    }

    private static ColorEntry color(String name, String value) {
        return new ColorEntry(name, "COLOR", value, List.of());
    }

    private static ColorEntry gradient(String name, List<String> colors) {
        return new ColorEntry(name, "GRADIENT", "", colors);
    }

    private static Map<String, String> defaultEmoji() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("heart", "\u2764");
        map.put("star", "\u2b50");
        map.put("fire", "\ud83d\udd25");
        map.put("smile", "\ud83d\ude04");
        map.put("sad", "\ud83d\ude22");
        map.put("cry", "\ud83d\ude2d");
        map.put("laugh", "\ud83d\ude02");
        map.put("cool", "\ud83d\ude0e");
        map.put("check", "\u2705");
        map.put("cross", "\u274c");
        map.put("skull", "\ud83d\udc80");
        map.put("creeper", "\ud83d\udc7d");
        return map;
    }

    private static FormatEntry entry(String permission, String format) {
        return new FormatEntry(permission, format);
    }

    private static FilterRule rule(String id, String type, String pattern, String action) {
        return new FilterRule(id, type, pattern, action);
    }
}
