package dev.yanianz.atrium.module.playtime;

import java.util.ArrayList;
import java.util.List;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/playtime.yml */
@ConfigSerializable
public class PlaytimeConfig {
    @Comment("Seconds between playtime samples. Active seconds are accumulated in a per-day bucket and\n"
            + "flushed to storage on every sample. 5 through 300.")
    public int sampleSeconds = 30;

    @Comment("Seconds between reward checks for each online player. 10 through 600.")
    public int rewardCheckSeconds = 60;

    @Comment("""
            Maximum grants a single reward may pay per check. 1 through 1000. Claims are durable, so any
            interval still owed is paid on a later check; keeping this small turns a bad watermark (or a
            crashed import) into a slow trickle instead of a burst of commands. 1 is the safe default.""")
    public int maxGrantsPerCheck = 1;

    @Comment("Seconds between asynchronous leaderboard snapshots. 30 through 3600.")
    public int leaderboardRefreshSeconds = 300;

    @Comment("Entries kept in the cached top snapshot. 10 through 10000.")
    public int leaderboardEntries = 500;

    @Comment("Entries shown per /playtimetop page. 1 through 100.")
    public int pageSize = 10;

    @Comment("Timezone used for day, week and month buckets (e.g. UTC, Asia/Jakarta). Changing it does not\n"
            + "move existing rows; new playtime is written under the new local date.")
    public String timeZone = "UTC";

    @Comment("""
            Playtime rewards. Each entry has:
              id:         unique [A-Za-z0-9_-] id; changing the interval of a released id changes its watermark (use a new id)
              type:       ONE_TIME (paid once when total playtime reaches seconds), REPEATING (paid every
                          seconds of total playtime) or DAILY (paid once per local day once today's active
                          seconds reach seconds; 0 means immediately each day)
              seconds:    threshold/interval; ONE_TIME and REPEATING require at least 60
              permission: optional permission the player must have to receive this reward
              commands:   console commands, run as the target player; {player} is replaced with the
                          validated player name
              money:      optional decimal amount deposited through the economy module
            Repeating rewards baseline themselves the first time they are seen, and an administrative set/add
            advances their watermark to the new total, so neither adding a reward nor importing a total pays
            a backlog. Claims (including which delivery steps already ran) are persisted before delivery is
            confirmed and retried after a failure.
            Example:
              rewards:
                - id: first-hour
                  type: ONE_TIME
                  seconds: 3600
                  permission: atrium.playtime.rewards
                  commands: ["give {player} diamond 1"]
                  money: "100"
                - id: hourly
                  type: REPEATING
                  seconds: 3600
                  commands: ["say {player} played another hour"]
                  money: "10"
                - id: daily
                  type: DAILY
                  seconds: 600
                  money: "25"
            """)
    public List<Reward> rewards = new ArrayList<>();

    @ConfigSerializable
    public static class Reward {
        public String id = "";
        public String type = "ONE_TIME";
        public long seconds = 3600;
        public String permission = "";
        public List<String> commands = new ArrayList<>();
        public String money = "";
    }
}
