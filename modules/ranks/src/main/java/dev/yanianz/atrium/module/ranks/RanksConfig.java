package dev.yanianz.atrium.module.ranks;

import java.util.ArrayList;
import java.util.List;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/ranks.yml */
@ConfigSerializable
public class RanksConfig {
    @Comment("""
            Rank ladder, lowest first. Each entry has:
              id:              unique [A-Za-z0-9_-] id used by /rankup, /ranks and /rankinfo
              display-name:    name shown to players; defaults to id
              group:           LuckPerms group to grant (removed from the previous ladder rank); empty disables
                               group changes and only runs commands
              playtime-seconds: total active playtime required through the playtime module
              money:           decimal cost charged through the economy module after every other requirement passed
              permission:      optional custom permission required to rank up
              commands:        console commands run once per rank per player; {player} is replaced with the
                               validated player name
              automatic:       allow the automatic timer to rank this player up when requirements are met
            Group names of two ranks must differ. Use a new rank id (not a renamed one) to make an old reward
            claim irrelevant.
            """)
    public List<Rank> ranks = new ArrayList<>(List.of(
            Rank.of("member", "Member", "default", 0, "0", "", true),
            Rank.of("veteran", "Veteran", "veteran", 7_200, "1000", "", true),
            Rank.of("elite", "Elite", "elite", 36_000, "5000", "atrium.ranks.elite", true)));

    @Comment("Rank given when a player has no stored rank. Must be one of the rank ids above.")
    public String startingRank = "member";

    @Comment("Run the automatic rank-up check for online players.")
    public boolean autoRankup = true;

    @Comment("Seconds between automatic rank-up cycles. 10 through 3600. Each player is checked on their own\n"
            + "thread; a failure for one player is logged and never stops the others.")
    public int autoRankupSeconds = 60;

    @Comment("Require /rankup confirm before charging money and granting the next rank.")
    public boolean confirmRankup = true;

    @Comment("Seconds a /rankup confirmation stays valid. 5 through 300. Requirements are checked again when\n"
            + "the confirmation is used.")
    public int confirmSeconds = 30;

    @Comment("Announce successful rank-ups to the whole server.")
    public boolean broadcastRankup = false;

    public boolean broadcastEnabled() { return broadcastRankup; }

    @ConfigSerializable
    public static class Rank {
        public String id = "member";
        public String displayName = "Member";
        public String group = "default";
        public long playtimeSeconds = 0;
        public String money = "0";
        public String permission = "";
        public List<String> commands = new ArrayList<>();
        public boolean automatic = true;

        static Rank of(String id, String displayName, String group, long playtimeSeconds, String money,
                String permission, boolean autoRankup) {
            Rank rank = new Rank();
            rank.id = id;
            rank.displayName = displayName;
            rank.group = group;
            rank.playtimeSeconds = playtimeSeconds;
            rank.money = money;
            rank.permission = permission;
            rank.automatic = autoRankup;
            return rank;
        }
    }
}
