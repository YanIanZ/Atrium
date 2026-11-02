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
            + "Placeholders: <player>, <display_name>, <world>, <message>, <tag_prefix>, <tag_suffix>.")
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

    @Comment("Per-player rate limit, duplicate message detection and optional similarity detection.")
    public AntiSpam antiSpam = new AntiSpam();

    @Comment("Rules are matched against normalised plain text, never against raw MiniMessage or colour codes.")
    public Filter filter = new Filter();

    @Comment("Highlight @name (or plain names) and play a sound for the mentioned player.")
    public Mentions mentions = new Mentions();

    @Comment("Number of blank lines sent by /clearchat.")
    public int clearChatLines = 100;

    @Comment("Custom join and quit messages. Default off: vanilla messages stay unless this is enabled.")
    public JoinQuit joinQuit = new JoinQuit();

    @Comment("Anti-caps filter. Default off; bypass with atrium.chat.caps.bypass.")
    public Caps caps = new Caps();

    @Comment("Advertisement filter for IP addresses and domains, with a domain whitelist. Default off.")
    public Ads ads = new Ads();

    @Comment("Repeated-command detection. Default off; bypass with atrium.chat.spam.bypass.")
    public CommandSpam commandSpam = new CommandSpam();

    @Comment("Local chat range, ! shout prefix, range spy and dynamic range by online count. Default off.")
    public Range range = new Range();

    @Comment("[item] and [item:slot] hover in chat.")
    public Items items = new Items();

    @Comment("Admin-whitelisted [sprite:...] tags. Unknown sprites stay literal text.")
    public Sprites sprites = new Sprites();

    @Comment("Player-selectable chat tags shown in the chat format and the tab list.")
    public Tags tags = new Tags();

    @Comment("Persistent private chat rooms. Members type in a room instead of public chat until they leave.")
    public Rooms rooms = new Rooms();

    @Comment("The [x] button staff can click to delete a chat message for everyone.")
    public DeleteButton deleteButton = new DeleteButton();

    @Comment("Staff sign advertisement spy. Default off; it only reports sign text, never changes it.")
    public SignSpy signSpy = new SignSpy();

    @ConfigSerializable
    public static class FormatEntry {
        @Comment("Permission required for this format; empty means every player.")
        public String permission = "";

        @Comment("Trusted MiniMessage format. Placeholders: <player>, <display_name>, <world>, <message>,\n"
                + "<tag_prefix> and <tag_suffix>.")
        public String format = "<gray><tag_prefix><player><tag_suffix><dark_gray> » <white><message>";

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

        @Comment("Also reject messages similar to recent ones (Levenshtein similarity). Default off.")
        public boolean similarityEnabled = false;

        @Comment("Similarity percentage above which two messages count as similar (1-100).")
        public int similarityPercentage = 80;

        @Comment("How many similar messages inside the duplicate window reject the new message.")
        public int similarityMinAmount = 2;
    }

    @ConfigSerializable
    public static class Filter {
        @Comment("Enable chat filter rules.")
        public boolean enabled = true;

        @Comment("Text used to replace a CENSOR match.")
        public String replacement = "***";

        @Comment("Action lines (actions module syntax) run when any rule matches. Variables: {player},\n"
                + "{message} and {rules}. Player text is inserted as plain text, never parsed.")
        public List<String> actions = new ArrayList<>();

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

        @Comment("Players with atrium.chat.filter.bypass.<group> skip this rule. Empty means no group.")
        public String group = "";

        @Comment("Action lines for this rule, run through the actions module when it matches.")
        public List<String> actions = new ArrayList<>();

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
    public static class JoinQuit {
        @Comment("Enable custom join and quit messages.")
        public boolean enabled = false;

        @Comment("Join message. Placeholders: <player>, <display_name>, <world>.")
        public String join = "<yellow><player> joined the game";

        @Comment("Quit message. Placeholders: <player>, <display_name>, <world>.")
        public String quit = "<yellow><player> left the game";

        @Comment("First-join message. Placeholders: <player>, <display_name>, <world>.")
        public String firstJoin = "<gold><player> joined for the first time";

        @Comment("Hide join/quit messages when more than this many players are online. 0 always shows them.")
        public int hideAbove = 0;

        @Comment("Worlds where join/quit messages are never shown.")
        public List<String> hiddenWorlds = new ArrayList<>();

        @Comment("Never announce a vanished player.")
        public boolean respectVanish = true;
    }

    @ConfigSerializable
    public static class Caps {
        @Comment("Enable the anti-caps check.")
        public boolean enabled = false;

        @Comment("Reject or lower-case a message when this percentage of its letters is upper case.")
        public int percentage = 60;

        @Comment("Messages with this many letters or fewer are ignored.")
        public int minLetters = 6;

        @Comment("Messages that match a whitelist entry exactly are allowed.")
        public List<String> whitelist = new ArrayList<>(List.of("OK", "GG", "LOL"));

        @Comment("Lower-case a rejected message instead of dropping it. The sender still sees it.")
        public boolean lowerCase = true;

        @Comment("Cancel a rejected message instead of showing the lower-cased version.")
        public boolean cancel = false;
    }

    @ConfigSerializable
    public static class Ads {
        @Comment("Enable the advertisement/IP filter.")
        public boolean enabled = false;

        @Comment("CENSOR hides the match, BLOCK rejects the message, WARN only alerts staff.")
        public String action = "CENSOR";

        @Comment("Replacement text for CENSOR.")
        public String replacement = "***";

        @Comment("Domains (and their subdomains) that are never treated as advertisements.")
        public List<String> whitelist = new ArrayList<>(List.of(
                "google.com", "youtube.com", "spigotmc.org", "minecraft.net", "mojang.com"));

        @Comment("Treat bare IPv4 addresses as advertisements.")
        public boolean blockIps = true;
    }

    @ConfigSerializable
    public static class CommandSpam {
        @Comment("Enable repeated-command detection.")
        public boolean enabled = false;

        @Comment("How often the same command may run inside the window.")
        public int maxRepeats = 3;

        @Comment("Window in seconds.")
        public long windowSeconds = 8L;

        @Comment("Commands that are never treated as spam (without the slash).")
        public List<String> whitelist = new ArrayList<>(List.of(
                "msg", "tell", "reply", "r", "w", "chatcolor", "chattag", "chatroom", "chatdelete", "emoji"));
    }

    @ConfigSerializable
    public static class Range {
        @Comment("Enable local chat range. Players outside the range do not receive the message.")
        public boolean enabled = false;

        @Comment("Default range in blocks.")
        public int defaultRange = 100;

        @Comment("Range in blocks for a ! shout. 0 uses defaultRange.")
        public int shoutRange = 0;

        @Comment("Prefix that turns a message into a shout.")
        public String shoutPrefix = "!";

        @Comment("Money charged for every shout. 0 is free.")
        public double shoutCost = 0.0;

        @Comment("Require atrium.chat.shout for the shout prefix.")
        public boolean requireShoutPermission = true;

        @Comment("Dynamic ranges by online count. The entry with the highest min-online that is <= the\n"
                + "online count wins; an empty list uses defaultRange.")
        public List<RangeStep> dynamic = new ArrayList<>();

        @Comment("Allow staff with atrium.chat.range.spy to toggle range spy with /rangespy.")
        public boolean allowSpy = true;
    }

    @ConfigSerializable
    public static class RangeStep {
        @Comment("Minimum online players for this range.")
        public int minOnline = 0;

        @Comment("Range in blocks.")
        public int range = 100;

        public RangeStep() {}

        RangeStep(int minOnline, int range) {
            this.minOnline = minOnline;
            this.range = range;
        }
    }

    @ConfigSerializable
    public static class Items {
        @Comment("Enable [item] and [item:slot] hover text.")
        public boolean enabled = true;

        @Comment("Maximum item tags rendered in one message (prevents oversized chat packets).")
        public int maxPerMessage = 5;

        @Comment("Require atrium.chat.item to render item tags.")
        public boolean requirePermission = true;
    }

    @ConfigSerializable
    public static class Sprites {
        @Comment("Enable [sprite:name] / [sprite:atlas:name] tags.")
        public boolean enabled = true;

        @Comment("Require atrium.chat.sprite (and atrium.chat.sprite.<id>) to render a sprite.")
        public boolean requirePermission = true;

        @Comment("Only sprites in this list can be used. id is the short name typed in chat.")
        public List<SpriteEntry> entries = new ArrayList<>(List.of(
                sprite("heart", "minecraft", "hud/heart/full"),
                sprite("food", "minecraft", "hud/food_full")));
    }

    @ConfigSerializable
    public static class SpriteEntry {
        @Comment("Short name typed as [sprite:<id>].")
        public String id = "heart";

        @Comment("Atlas key (minecraft is the default atlas).")
        public String atlas = "minecraft";

        @Comment("Sprite path inside the atlas.")
        public String sprite = "hud/heart/full";

        public SpriteEntry() {}

        SpriteEntry(String id, String atlas, String sprite) {
            this.id = id;
            this.atlas = atlas;
            this.sprite = sprite;
        }
    }

    @ConfigSerializable
    public static class Tags {
        @Comment("Enable player-selectable chat tags.")
        public boolean enabled = true;

        @Comment("Also apply the selected tag to the player's tab list name through the tablist service.")
        public boolean showInTabList = true;

        @Comment("Tags have id, prefix, suffix and permission. The first entry the player may use and\n"
                + "that they selected with /chattag is used.")
        public List<TagEntry> entries = new ArrayList<>(List.of(
                tag("vip", "<gold>[VIP]</gold> ", "", "atrium.chat.tag.vip"),
                tag("staff", "<red>[STAFF]</red> ", "", "atrium.chat.tag.staff")));
    }

    @ConfigSerializable
    public static class TagEntry {
        @Comment("Short name used by /chattag.")
        public String id = "vip";

        @Comment("Trusted MiniMessage shown before the player name.")
        public String prefix = "";

        @Comment("Trusted MiniMessage shown after the player name.")
        public String suffix = "";

        @Comment("Permission required to select this tag.")
        public String permission = "atrium.chat.tag.vip";

        public TagEntry() {}

        TagEntry(String id, String prefix, String suffix, String permission) {
            this.id = id;
            this.prefix = prefix;
            this.suffix = suffix;
            this.permission = permission;
        }
    }

    @ConfigSerializable
    public static class Rooms {
        @Comment("Enable persistent private chat rooms.")
        public boolean enabled = true;

        @Comment("Maximum rooms one player may own.")
        public int maxRoomsPerPlayer = 3;

        @Comment("Maximum members in one room.")
        public int maxMembers = 50;

        @Comment("Longest room name (letters, digits, underscore and dash).")
        public int maxNameLength = 24;

        @Comment("Allow joining any unlocked room without an invitation.")
        public boolean allowPublicJoin = true;
    }

    @ConfigSerializable
    public static class DeleteButton {
        @Comment("Show the delete button to staff with atrium.chat.delete.")
        public boolean enabled = true;

        @Comment("How many recent messages are kept for deletion.")
        public int historySize = 200;

        @Comment("Button label appended to every chat line for staff.")
        public String label = " [x]";

        @Comment("Hover text of the delete button.")
        public String hover = "<red>Delete this message for everyone";
    }

    @ConfigSerializable
    public static class SignSpy {
        @Comment("Enable the sign advertisement spy.")
        public boolean enabled = false;

        @Comment("How many sign lines are reported.")
        public int maxLines = 4;
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

    private static SpriteEntry sprite(String id, String atlas, String sprite) {
        return new SpriteEntry(id, atlas, sprite);
    }

    private static TagEntry tag(String id, String prefix, String suffix, String permission) {
        return new TagEntry(id, prefix, suffix, permission);
    }
}
