package dev.yanianz.atrium.module.placeholders;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/placeholders.yml */
@ConfigSerializable
public class PlaceholdersConfig {
    @Comment("Seconds between refreshes of the per-player cache (1-5). The cache is filled on the player's\n"
            + "own thread, so PlaceholderAPI may read it from any thread.")
    public int refreshIntervalSeconds = 2;

    @Comment("Extra seconds a cached value stays usable after the last refresh (0-60). Values older than\n"
            + " refreshIntervalSeconds + this are treated as unavailable.")
    public int cacheGraceSeconds = 5;

    @Comment("Seconds between storage lookups for balance, playtime and rank of an online player (5-300).\n"
            + "Nothing is ever read from storage inside a PlaceholderAPI request.")
    public int storageRefreshSeconds = 30;

    @Comment("Text returned when a service is missing or a value is unknown. An empty value returns nothing.")
    public String fallback = "";

    @Comment("Text returned by boolean placeholders (afk, vanished, muted, shadow_muted) when true.")
    public String trueText = "true";

    @Comment("Text returned by boolean placeholders (afk, vanished, muted, shadow_muted) when false.")
    public String falseText = "false";

    @Comment("""
            Expose Bukkit statistics as %atrium_stat_<name>% (raw) and %atrium_stat_<name>_formatted%
            (duration for time statistics, metres/kilometres for distances). Values are read on each
            player's own thread and only cached; a request never touches the player.""")
    public boolean statisticsEnabled = true;

    @Comment("Seconds between statistics reads per player (10-600). Only the plain counters are read by\n"
            + "default, so this is a few dozen cheap lookups per interval.")
    public int statisticsRefreshSeconds = 60;

    @Comment("""
            Also sum statistics Bukkit stores per material or entity (mine_block, break_item, kill_entity,
            ...). This touches every registry entry for each player and interval; leave it off unless a
            scoreboard really needs those totals.""")
    public boolean statisticsTypedTotals = false;

    @Comment("Expose custom player metadata as %atrium_user_meta_<key>% (managed with /usermeta).")
    public boolean userMetaEnabled = true;
}
