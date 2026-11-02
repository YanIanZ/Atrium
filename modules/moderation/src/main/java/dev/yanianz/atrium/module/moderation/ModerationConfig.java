package dev.yanianz.atrium.module.moderation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/moderation.yml */
@ConfigSerializable
public class ModerationConfig {
    @Comment("Reason stored when a command is used without a reason.")
    public String defaultReason = "No reason given";

    @Comment("Longer reasons are cut to this many characters.")
    public int maxReasonLength = 256;

    @Comment("Reason presets: typing exactly the key (optionally with a leading #) as the reason stores the text.\n"
            + "Example: /ban Steve hacking")
    public Map<String, String> reasonPresets = defaultPresets();

    @Comment("When the ban check cannot read the database during login, refuse the login (true, safe) or let\n"
            + "the player in (false).")
    public boolean refuseLoginWhenCheckFails = true;

    @Comment("Commands may target players who never joined: their UUID is looked up by name (Mojang in online\n"
            + "mode, the offline UUID otherwise), so accounts can be banned in advance.")
    public boolean resolveUnknownNames = true;

    @Comment("Entries shown per page by /history, /warnings and /editwarnings.")
    public int pageSize = 8;

    @Comment("Date format of /history (Java DateTimeFormatter pattern, server time zone).")
    public String dateFormat = "yyyy-MM-dd HH:mm";

    @Comment("Feedback settings for every punishment command.")
    public Feedback feedback = new Feedback();

    @Comment("Mute settings.")
    public Mute mute = new Mute();

    @Comment("Jail settings.")
    public Jail jail = new Jail();

    @Comment("Freeze settings.")
    public Freeze freeze = new Freeze();

    @Comment("Warning settings.")
    public Warnings warnings = new Warnings();

    @Comment("Login address lock settings (/lockip).")
    public LockIp lockIp = new LockIp();

    @Comment("Cross-server moderation settings (requires the proxy module).")
    public Sync sync = new Sync();

    @ConfigSerializable
    public static class Feedback {
        @Comment("Send the issuer a one-line summary (id, type, target, reason, duration) after every punishment\n"
                + "they apply or lift, including edits made with /editwarnings and /history.")
        public boolean summary = true;

        @Comment("Send the public broadcast line to every player. When false, only staff with\n"
                + "atrium.moderation.notify see it (still honoured together with -s).")
        public boolean broadcastPublic = true;

        @Comment("Send the broadcast line to staff (atrium.moderation.notify) in addition to the public line.\n"
                + "Silent punishments (-s) always go to staff only.")
        public boolean broadcastStaff = true;
    }

    @ConfigSerializable
    public static class LockIp {        @Comment("Tell online holders of atrium.moderation.notify when a login is refused because the account\n"
                + "may only join from its locked addresses.")
        public boolean notifyStaff = true;
    }

    @ConfigSerializable
    public static class Sync {
        @Comment("Publish bans, unbans, mutes, unmutes and kicks on the authenticated 'moderation' proxy topic.\n"
                + "Bans are shared through storage when MySQL is used; with SQLite only kicks and mutes\n"
                + "take effect live on the other servers. Payloads from other servers are validated.")
        public boolean enabled = true;
    }

    @ConfigSerializable
    public static class Mute {
        @Comment("Commands a muted player cannot use. Matched word by word: 'msg' blocks /msg and /minecraft:msg,\n"
                + "not /msgtoggle; 'mail send' blocks /mail send but not /mail read. Shadow-muted players are\n"
                + "blocked silently.")
        public List<String> blockedCommands = new ArrayList<>(List.of(
                "me", "say", "msg", "tell", "w", "whisper", "m", "t", "pm", "r", "reply", "mail send",
                "teammsg", "tm", "broadcast", "bc", "helpop"));

        @Comment("Muted players cannot write text on signs.")
        public boolean blockSigns = true;

        @Comment("Muted players cannot write or sign books.")
        public boolean blockBooks = true;
    }

    @ConfigSerializable
    public static class Jail {
        @Comment("Jail used by /jail when no jail name is given. When it does not exist, the first jail is used.")
        public String defaultJail = "default";

        @Comment("Radius (blocks) of the jail area used by /setjail when none is given.")
        public double defaultRadius = 8.0;

        @Comment("Commands a jailed player cannot use. '*' means every command.")
        public List<String> blockedCommands = new ArrayList<>(List.of("*"));

        @Comment("Exceptions to blockedCommands (word-by-word matching, see mute.blockedCommands). A jail can\n"
                + "override this list with /jailedit <jail> allowed; an empty per-jail list means this one.")
        public List<String> allowedCommands = new ArrayList<>(List.of("msg", "tell", "r", "reply", "rules", "helpop"));

        @Comment("Jailed players cannot chat. A jail can override this with /jailedit <jail> chat <true|false>.")
        public boolean chatAllowed = true;

        @Comment("Jailed players cannot mount vehicles or animals (riding does not fire movement checks reliably).")
        public boolean blockVehicles = true;

        @Comment("When the jail time is over or the player is unjailed, return them to where they were before\n"
                + "being jailed. When no location was stored (or its world is not loaded), see releaseToSpawn.")
        public boolean releaseToStoredLocation = true;

        @Comment("Fallback when no release location is available: teleport the player to the world spawn.")
        public boolean releaseToSpawn = true;
    }

    @ConfigSerializable
    public static class Freeze {
        @Comment("Commands a frozen player cannot use. '*' means every command.")
        public List<String> blockedCommands = new ArrayList<>(List.of("*"));

        @Comment("Exceptions to blockedCommands.")
        public List<String> allowedCommands = new ArrayList<>(List.of("msg", "tell", "r", "reply", "helpop"));

        @Comment("Frozen players take no damage (so nobody can kill a player who cannot move).")
        public boolean invulnerable = true;
    }

    @ConfigSerializable
    public static class Warnings {
        @Comment("How long a warning counts towards escalation (e.g. 30d, 12h). '0' = warnings never expire.")
        public String expireAfter = "30d";

        @Comment("Rules run as console when the number of active warnings reaches 'warnings'. Each rule fires once\n"
                + "when the count crosses its threshold. Placeholders: {player}, {uuid}, {count}.")
        public List<EscalationRule> escalation = new ArrayList<>(List.of(
                EscalationRule.of(3, "tempmute {player} 1h -s Reached {count} warnings"),
                EscalationRule.of(5, "kick {player} -s Reached {count} warnings"),
                EscalationRule.of(7, "tempban {player} 1d -s Reached {count} warnings")));
    }

    @ConfigSerializable
    public static class EscalationRule {
        @Comment("Number of active warnings that triggers this rule.")
        public int warnings = 3;

        @Comment("Console commands to run (without slash).")
        public List<String> commands = new ArrayList<>();

        static EscalationRule of(int warnings, String... commands) {
            EscalationRule rule = new EscalationRule();
            rule.warnings = warnings;
            rule.commands = new ArrayList<>(List.of(commands));
            return rule;
        }
    }

    private static Map<String, String> defaultPresets() {
        Map<String, String> presets = new LinkedHashMap<>();
        presets.put("hacking", "Use of a modified client or cheats");
        presets.put("spam", "Spamming");
        presets.put("grief", "Griefing");
        presets.put("toxic", "Toxic behaviour");
        presets.put("advertising", "Advertising other servers");
        return presets;
    }
}
