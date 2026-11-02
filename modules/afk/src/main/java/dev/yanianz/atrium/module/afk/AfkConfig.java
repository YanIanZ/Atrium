package dev.yanianz.atrium.module.afk;

import java.util.ArrayList;
import java.util.List;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/afk.yml */
@ConfigSerializable
public class AfkConfig {
    @Comment("Seconds without activity before a player is marked AFK automatically. 0 disables automatic AFK.")
    public int autoAfkSeconds = 300;

    @Comment("How often online players are checked for idleness, in seconds. 1 through 60.")
    public int checkIntervalSeconds = 1;

    @Comment("Broadcast when a player becomes AFK.")
    public boolean broadcastOnEnter = true;

    @Comment("Broadcast when a player stops being AFK.")
    public boolean broadcastOnLeave = true;

    @Comment("Movement in blocks (horizontal or vertical) needed to count as activity. 0.01 through 10.")
    public double movementThreshold = 0.1;

    @Comment("Count head rotation alone as activity. Keep this off: a macro that only turns the camera is not\n"
            + "real activity. Movement and all listed actions always count.")
    public boolean countRotationAsActivity = false;

    @Comment("Yaw or pitch change in degrees that counts as activity when countRotationAsActivity is on.")
    public double rotationThresholdDegrees = 2.0;

    @Comment("Ignore movement caused by water, lava, vehicles and sudden piston-like pushes.")
    public boolean ignorePushed = true;

    @Comment("A single movement larger than this many blocks is treated as a push or teleport and ignored.\n"
            + "Must be at least movementThreshold.")
    public double maxMovementPerCheck = 4.0;

    @Comment("Do not count repeated tiny movement inside a small box as activity. Useful against players\n"
            + "who walk in a one-block pool with a macro.")
    public boolean antiAfkPoolEnabled = true;

    @Comment("Horizontal box size in blocks used by the anti-AFK-pool check. A player must leave this box,\n"
            + "chat, interact or click an inventory slot to be considered active after the window.")
    public double antiAfkPoolBoxSize = 3.0;

    @Comment("Grace window in seconds. Movement inside the box counts for this long, then stops counting\n"
            + "until the player leaves the box or performs another activity. This prevents one free\n"
            + "activity per window from a macro.")
    public int antiAfkPoolWindowSeconds = 60;

    @Comment("Minutes of AFK before the actions below run. 0 disables all AFK actions.")
    public int actionsAfterMinutes = 10;

    @Comment("Kick players after the actions run, unless they have atrium.afk.kickexempt.")
    public boolean kick = false;

    @Comment("Console commands run once per AFK session. {player} is replaced with the player name after it\n"
            + "passes the [A-Za-z0-9_]{1,16} check. Example: [\"say {player} is AFK\"]")
    public List<String> commands = new ArrayList<>();

    @Comment("Show a title when the AFK actions run.")
    public boolean title = false;

    @Comment("Send an action bar message when the AFK actions run.")
    public boolean actionBar = false;

    @Comment("Make AFK players invulnerable while they are AFK. The previous value is restored on leave.")
    public boolean invulnerableWhileAfk = false;

    @Comment("Exclude AFK players from sleep counting with setSleepingIgnored. The previous value is restored\n"
            + "on leave and on quit.")
    public boolean sleepingIgnoredWhileAfk = true;

    @Comment("Maximum length of a message passed to /afk. 1 through 1024.")
    public int maxMessageLength = 256;

    @Comment("Tag added to the player's tab list name while they are AFK (MiniMessage, trusted admin text). It is\n"
            + "composed by the tablist module into the name it renders, so it works with the native and the TAB\n"
            + "engine and never stacks with TAB's own name. Empty disables it. Needs the tablist module with\n"
            + "set-list-name: true; without the tablist module no tag is shown.")
    public String tabListTag = " <gray>[AFK]</gray>";

    @Comment("Where the tab list tag goes: before or after the name.")
    public String tabListTagPosition = "after";

    @Comment("AFK players are not chosen as a target by mobs, and shulker bullets let go of an AFK target.\n"
            + "This only affects targeting, never damage from a projectile that is already in flight.")
    public boolean protectFromTargeting = true;

    @Comment("Interacting with an entity (a villager, a chest boat, an item frame, ...) ends AFK.")
    public boolean exitOnEntityInteract = true;
}
