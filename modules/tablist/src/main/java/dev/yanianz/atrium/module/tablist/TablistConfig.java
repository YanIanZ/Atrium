package dev.yanianz.atrium.module.tablist;

import dev.yanianz.atrium.module.display.AnimationConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/tablist.yml */
@ConfigSerializable
public class TablistConfig {
    @Comment("""
            Engine that shows the tab list:
              auto   - tab when the TAB plugin (by NEZNAMY) is enabled, otherwise native;
              native - Atrium sends the header, footer, list names and list order itself;
              tab    - Atrium renders everything below and hands it to TAB through TAB's API; Atrium then sends
                       none of it itself. Each part needs the matching TAB feature (header-footer,
                       tablist-name-formatting, sorting through scoreboard-teams); a part whose TAB feature is
                       disabled is left to TAB's own config (logged once). Falls back to native when TAB is missing.
            With tab, list names replace TAB's prefix/name/suffix for each player, and sorting uses TAB team names.
            Check the active engine with /tablist engine. Changes apply on /atrium reload.""")
    public String engine = "auto";

    @Comment("""
            Ticks between tab list updates (20 ticks = 1 second), 2 through 1200. One global timer advances the
            animations; each player is then rendered on their own thread and only receives packets when their
            header, footer, list name or order actually changed.""")
    public int updateIntervalTicks = 10;

    @Comment("""
            Header lines (MiniMessage, trusted admin text). Placeholders: <player> <displayname> <online> <max>
            <world> <ping> <tps> <balance> <playtime> <rank>, animations as <anim:name>, and %placeholderapi%
            tokens when PlaceholderAPI is installed and placeholder-api is true. An empty list clears the header.""")
    public List<String> header = new ArrayList<>(List.of(
            "",
            "<anim:title>",
            "<gray>Welcome, <white><displayname></white>",
            ""));

    @Comment("Footer lines, same format as header.")
    public List<String> footer = new ArrayList<>(List.of(
            "",
            "<gray>Online: <white><online></white>/<white><max></white>  <dark_gray>|</dark_gray>  Ping: <white><ping>ms</white>  <dark_gray>|</dark_gray>  TPS: <white><tps></white>",
            ""));

    @Comment("""
            Header/footer overrides, checked top to bottom; the first one whose worlds and permission match the
            player wins. worlds: world names (empty = any world). permission: required permission (empty = none).
            header/footer: an empty list keeps the default one; use [''] for a blank header or footer.""")
    public List<HeaderOverride> overrides = new ArrayList<>(List.of(HeaderOverride.example()));

    @Comment("""
            Animations usable as <anim:name> in the header, footer and list name formats. Each has frames and
            either interval-ticks (same duration for every frame) or frame-ticks (one duration per frame).""")
    public Map<String, AnimationConfig> animations = new LinkedHashMap<>(Map.of(
            "title", AnimationConfig.of(10, List.of(40, 10, 10),
                    "<gradient:#5fb3a1:#3b7f9a><bold>Atrium</bold></gradient>",
                    "<gradient:#3b7f9a:#5fb3a1><bold>Atrium</bold></gradient>",
                    "<white><bold>Atrium</bold></white>")));

    @Comment("Set each player's name in the tab list from the format of their group.")
    public boolean setListName = true;

    @Comment("""
            Groups in display order: the first group is listed at the top of the tab list. A player belongs to the
            first group whose permission they have; players without any group use default-list-name.
            list-name: player list name format (same placeholders; <displayname> respects nicknames).""")
    public List<Group> groups = new ArrayList<>(List.of(
            Group.of("admin", "atrium.tablist.group.admin", "<red>[Admin]</red> <displayname>"),
            Group.of("moderator", "atrium.tablist.group.moderator", "<gold>[Mod]</gold> <displayname>"),
            Group.of("vip", "atrium.tablist.group.vip", "<green>[VIP]</green> <displayname>")));

    @Comment("List name format for players who are in no group.")
    public String defaultListName = "<gray><displayname>";

    @Comment("""
            Sort the tab list by group order (Paper's player list order, no scoreboard teams). Inside a group the
            client sorts by account name.""")
    public boolean sortByGroup = true;

    @Comment("""
            Also sort by the visible name (nickname) inside a group. This gives every player a distinct order value,
            so one join can shift many players and send more packets; leave false on large servers.""")
    public boolean sortByDisplayName = false;

    @Comment("Resolve %placeholderapi% tokens in the templates when PlaceholderAPI is installed.")
    public boolean placeholderApi = true;

    @Comment("Text shown for <balance>, <playtime>, <rank> or <tps> when the providing module is missing or still loading.")
    public String missingValue = "-";

    @Comment("Seconds between background refreshes of <rank>, <playtime> and uncached <balance> values, 5 through 3600.")
    public int asyncRefreshSeconds = 30;

    @ConfigSerializable
    public static class HeaderOverride {
        @Comment("World names (case-insensitive). Empty = any world.")
        public List<String> worlds = new ArrayList<>();
        @Comment("Permission required for this override. Empty = everyone.")
        public String permission = "";
        @Comment("Header lines for matching players; empty keeps the default header.")
        public List<String> header = new ArrayList<>();
        @Comment("Footer lines for matching players; empty keeps the default footer.")
        public List<String> footer = new ArrayList<>();

        static HeaderOverride example() {
            HeaderOverride override = new HeaderOverride();
            override.worlds = new ArrayList<>(List.of("world_nether"));
            override.header = new ArrayList<>(List.of("", "<anim:title> <red>Nether</red>", ""));
            return override;
        }
    }

    @ConfigSerializable
    public static class Group {
        @Comment("Group id (letters, digits, _ and -).")
        public String name = "default";
        @Comment("Permission that puts a player in this group.")
        public String permission = "";
        @Comment("Player list name format for this group.")
        public String listName = "<displayname>";

        static Group of(String name, String permission, String listName) {
            Group group = new Group();
            group.name = name;
            group.permission = permission;
            group.listName = listName;
            return group;
        }
    }
}
