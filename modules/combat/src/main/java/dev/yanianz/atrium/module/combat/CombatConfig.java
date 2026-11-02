package dev.yanianz.atrium.module.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.event.entity.EntityDamageEvent;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/combat.yml */
@ConfigSerializable
public class CombatConfig {
    @Comment("Combat gameplay is enabled or disabled per world, keyed by world name. The \"*\" entry is the\n"
            + "fallback for worlds without an entry. Players are not tagged in a disabled world and an existing\n"
            + "tag ends when they enter one.")
    public Map<String, Boolean> worlds = defaultWorlds();

    @Comment("PvP combat timer: who is tagged, which commands are blocked and how the timer is displayed.")
    public Tag tag = new Tag();

    @Comment("Punishment for leaving the server while tagged. Off by default; nothing is taken while disabled.")
    public Logout logout = new Logout();

    @Comment("Per-cause damage multipliers selected by permission. Off by default.")
    public DamageControl damageControl = new DamageControl();

    @Comment("Players who make mobs ignore them with /notarget.")
    public Notarget notarget = new Notarget();

    private static Map<String, Boolean> defaultWorlds() {
        Map<String, Boolean> map = new LinkedHashMap<>();
        map.put("*", Boolean.TRUE);
        return map;
    }

    boolean worldEnabled(String worldName) {
        return CombatRules.worldEnabled(worlds, worldName, true);
    }

    /** Reports configuration mistakes at load time instead of failing silently at runtime. */
    void validate(Consumer<String> warn) {
        if (tag == null) {
            warn.accept("tag: missing section, PvP tagging stays off");
            tag = new Tag();
        }
        if (tag.seconds() != tag.seconds) {
            warn.accept("tag.seconds: clamped to 1-600");
        }
        if (tag.mobSeconds() != tag.mobSeconds) {
            warn.accept("tag.mob-seconds: clamped to 1-600");
        }
        if (CombatRules.parseDisplay(tag.display) == null) {
            warn.accept("tag.display: unknown value '" + tag.display + "' (NONE, ACTIONBAR or BOSSBAR)");
        }
        if (tag.bossBarColor() == null) {
            warn.accept("tag.boss-bar-color: unknown colour '" + tag.bossBarColor + "'");
        }
        if (logout.parsedMode() == null) {
            warn.accept("logout.mode: unknown value '" + logout.mode + "' (DROP, KILL or NONE)");
        }
        if (logout == null) {
            warn.accept("logout: missing section, combat-log punishment stays off");
            logout = new Logout();
        }
        if (damageControl == null) {
            warn.accept("damage-control: missing section, damage control stays off");
            damageControl = new DamageControl();
        }
        if (damageControl.rules != null) {
            for (int i = 0; i < damageControl.rules.size(); i++) {
                DamageRule rule = damageControl.rules.get(i);
                String path = "damage-control.rules[" + i + "]";
                if (rule == null) {
                    warn.accept(path + ": empty rule");
                    continue;
                }
                if (!DamageControlRules.matchesAll(rule.cause) && !knownCause(rule.cause)) {
                    warn.accept(path + ": unknown damage cause '" + rule.cause + "'");
                }
                if (!Double.isFinite(rule.multiplier) || rule.multiplier < 0) {
                    warn.accept(path + ": multiplier clamped to 0-100");
                }
                for (String permission : rule.permissions) {
                    if (permission == null || permission.isBlank()) {
                        warn.accept(path + ": blank permission entry ignored");
                    }
                }
                for (String world : rule.worlds) {
                    if (world == null || world.isBlank()) {
                        warn.accept(path + ": blank world entry ignored");
                    }
                }
            }
        }
        if (notarget == null) {
            warn.accept("notarget: missing section, the no-target toggle stays off");
            notarget = new Notarget();
        }
        if (worlds != null) {
            for (Map.Entry<String, Boolean> entry : worlds.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    warn.accept("worlds: blank world name");
                }
                if (entry.getValue() == null) {
                    warn.accept("worlds." + entry.getKey() + ": null treated as enabled");
                }
            }
        }
    }

    private static boolean knownCause(String raw) {
        String name = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (EntityDamageEvent.DamageCause cause : EntityDamageEvent.DamageCause.values()) {
            if (cause.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    @ConfigSerializable
    public static class Tag {
        @Comment("Tag players after PvP. Off disables the timer, its command block and its flight block.")
        public boolean enabled = true;

        @Comment("Seconds a PvP hit keeps a player tagged (1-600). Every new hit extends the timer.")
        public int seconds = 15;

        @Comment("Also tag the player who took the damage. Off tags only the attacker.")
        public boolean tagVictim = true;

        @Comment("Tag a player who fights a mob, and a player hit by a mob. Mobs never get a tag themselves.")
        public boolean mobCombat = false;

        @Comment("Seconds a mob fight keeps a player tagged (1-600).")
        public int mobSeconds = 8;

        @Comment("Cancel the commands in blocked-commands while a player is tagged.")
        public boolean blockCommands = true;

        @Comment("Commands blocked while tagged (without the leading slash). Matching is word by word, so\n"
                + "'home' blocks '/home' and '/home 2' but not '/homeless'. The command namespace is ignored,\n"
                + "so '/essentials:home' is blocked as 'home'.")
        public List<String> blockedCommands = new ArrayList<>(List.of(
                "home", "sethome", "warp", "spawn", "tpa", "tpahere", "tpaccept", "tpyes",
                "back", "rtp", "wild", "jump", "tppos", "tp", "tphere", "tpo"));

        @Comment("Commands that stay usable while tagged. An entry here wins over blocked-commands.")
        public List<String> allowedCommands = new ArrayList<>(List.of("msg", "tell", "r", "reply", "help"));

        @Comment("Stop players from flying while tagged. Flight is removed through the player module's\n"
                + "FlightService claims; the player must enable /fly again after the tag ends.")
        public boolean blockFlight = true;

        @Comment("Stop elytra gliding while tagged.")
        public boolean blockElytra = true;

        @Comment("Cancel Atrium teleports (homes, warps, spawn, /back, ...) while tagged. Teleports started by\n"
                + "plugins and the death-back teleport stay allowed.")
        public boolean cancelTeleports = true;

        @Comment("Do not tag a fight inside a claim/region where the attacker may not interact (ProtectionService).\n"
                + "Damage cancelled by a protection plugin never tags either way.")
        public boolean respectProtection = true;

        @Comment("How the remaining tag is shown: NONE, ACTIONBAR or BOSSBAR.")
        public String display = "ACTIONBAR";

        @Comment("Boss bar colour while tagged: PINK, BLUE, RED, GREEN, YELLOW, PURPLE or WHITE.")
        public String bossBarColor = "RED";

        @Comment("Send a chat line when a tag starts and ends. The action bar/boss bar timer is independent.")
        public boolean notify = true;

        int seconds() {
            return clamp(seconds, 1, 600);
        }

        int mobSeconds() {
            return clamp(mobSeconds, 1, 600);
        }

        net.kyori.adventure.bossbar.BossBar.Color bossBarColor() {
            if (bossBarColor == null) {
                return null;
            }
            try {
                return net.kyori.adventure.bossbar.BossBar.Color.valueOf(bossBarColor.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        net.kyori.adventure.bossbar.BossBar.Color resolvedBossBarColor() {
            net.kyori.adventure.bossbar.BossBar.Color parsed = bossBarColor();
            return parsed == null ? net.kyori.adventure.bossbar.BossBar.Color.RED : parsed;
        }
    }

    @ConfigSerializable
    public static class Logout {
        @Comment("Punish leaving the server while tagged.")
        public boolean enabled = false;

        @Comment("What the punishment does: DROP drops the carried items at the logout spot, KILL lets the\n"
                + "player die (vanilla death drops, so keep-inventory rules still apply), NONE only announces it.")
        public String mode = "DROP";

        @Comment("Also drop the experience of the player (DROP mode only).")
        public boolean dropExperience = false;

        @Comment("Announce the combat log to everyone online.")
        public boolean broadcast = true;

        LogoutMode parsedMode() {
            if (mode == null || mode.isBlank()) {
                return LogoutMode.DROP;
            }
            try {
                return LogoutMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        LogoutMode mode() {
            LogoutMode parsed = parsedMode();
            return parsed == null ? LogoutMode.DROP : parsed;
        }

        public enum LogoutMode { DROP, KILL, NONE }
    }

    @ConfigSerializable
    public static class DamageControl {
        @Comment("Apply the rules below to damage taken by players.")
        public boolean enabled = false;

        @Comment("Ordered rules. The first rule that matches the cause, the world and the player's permissions\n"
                + "wins. A multiplier of 1 leaves the damage alone, 0.5 halves it and 0 removes it completely.\n"
                + "Example:\n"
                + "  - cause: FALL\n"
                + "    permissions: [rank.vip]\n"
                + "    multiplier: 0.5\n"
                + "    worlds: []")
        public List<DamageRule> rules = new ArrayList<>();
    }

    @ConfigSerializable
    public static class DamageRule {
        @Comment("Damage cause name such as FALL, ENTITY_ATTACK, FIRE_TICK or '*'. A dash may replace the\n"
                + "underscore (FIRE-TICK).")
        public String cause = "FALL";

        @Comment("Permissions of which the player needs at least one. An empty list matches every player.")
        public List<String> permissions = new ArrayList<>();

        @Comment("Damage multiplier (0-100). 0 removes the damage, 1 keeps it unchanged.")
        public double multiplier = 1.0;

        @Comment("Worlds the rule applies to. An empty list matches every world.")
        public List<String> worlds = new ArrayList<>();
    }

    @ConfigSerializable
    public static class Notarget {
        @Comment("Allow players to toggle /notarget. Off hides the command's effect.")
        public boolean enabled = true;

        @Comment("Keep the TEMPT target reason (a mob walking to a player holding food keeps doing so).")
        public boolean ignoreTempt = true;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
