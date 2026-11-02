package dev.yanianz.atrium.module.scoreboard;

import dev.yanianz.atrium.module.display.AnimationConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/scoreboard.yml */
@ConfigSerializable
public class ScoreboardConfig {
    @Comment("""
            Ticks between sidebar updates (20 ticks = 1 second), 2 through 1200. Each player is rendered on their own
            thread and only lines whose text changed are sent.
            On Paper every player gets a private scoreboard (the server's main scoreboard used by other plugins and
            vanilla commands is never modified). If another plugin already gave a player its own scoreboard, Atrium
            leaves that player alone.""")
    public int updateIntervalTicks = 10;

    @Comment("""
            Rendering backend (engine):
              auto    - tab when the TAB plugin runs its scoreboard feature; otherwise the Bukkit scoreboard API on
                        Paper, or PacketEvents on Folia when that plugin is installed;
              bukkit  - always the Bukkit scoreboard API (not available on Folia);
              packets - always PacketEvents packets, also on Paper (requires the packetevents plugin);
              tab     - the TAB plugin's scoreboard API: Atrium renders the boards below per player and TAB shows
                        them. Needs TAB with its scoreboard feature enabled in TAB's config; otherwise Atrium falls
                        back to the auto choice without TAB and logs why once. When Atrium hides its sidebar (no
                        matching board, or /sidebar toggle), TAB's own scoreboards take the slot back; the score
                        number format follows TAB's config.
            Folia has no scoreboard API, so without PacketEvents or TAB sidebars stay hidden there.
            /sidebar engine shows the active backend. Changes apply on /atrium reload.""")
    public String backend = "auto";

    @Comment("""
            Sidebar boards, checked top to bottom; a player sees the first board whose worlds and permission match.
            worlds: world names (empty = any). permission: required permission (empty = everyone).
            title and lines are MiniMessage (trusted admin text) with <player> <displayname> <online> <max> <world>
            <ping> <tps> <balance> <playtime> <rank>, <anim:name> and %placeholderapi% tokens. At most 15 lines.""")
    public List<Board> boards = new ArrayList<>(List.of(Board.example()));

    @Comment("Animations usable as <anim:name> in titles and lines (frames + interval-ticks or frame-ticks).")
    public Map<String, AnimationConfig> animations = new LinkedHashMap<>(Map.of(
            "title", AnimationConfig.of(8, List.of(),
                    "<gradient:#5fb3a1:#3b7f9a><bold>Atrium</bold></gradient>",
                    "<gradient:#3b7f9a:#5fb3a1><bold>Atrium</bold></gradient>")));

    @Comment("Resolve %placeholderapi% tokens when PlaceholderAPI is installed.")
    public boolean placeholderApi = true;

    @Comment("Text shown for <balance>, <playtime>, <rank> or <tps> when the providing module is missing or still loading.")
    public String missingValue = "-";

    @Comment("Seconds between background refreshes of <rank>, <playtime> and uncached <balance> values, 5 through 3600.")
    public int asyncRefreshSeconds = 30;

    @ConfigSerializable
    public static class Board {
        @Comment("Board id, used in logs.")
        public String name = "default";
        @Comment("World names (case-insensitive). Empty = any world.")
        public List<String> worlds = new ArrayList<>();
        @Comment("Permission required to see this board. Empty = everyone.")
        public String permission = "";
        @Comment("Sidebar title.")
        public String title = "<anim:title>";
        @Comment("Lines from top to bottom, at most 15. Empty strings are blank lines.")
        public List<String> lines = new ArrayList<>();

        static Board example() {
            Board board = new Board();
            board.lines = new ArrayList<>(List.of(
                    "",
                    "<gray>Player: <white><displayname>",
                    "<gray>Rank: <white><rank>",
                    "<gray>Balance: <green><balance>",
                    "",
                    "<gray>World: <white><world>",
                    "<gray>Online: <white><online></white>/<white><max>",
                    "<gray>Playtime: <white><playtime>",
                    "",
                    "<dark_gray>/sidebar toggle"));
            return board;
        }
    }
}
