package dev.yanianz.atrium.module.commandrules;

import java.util.ArrayList;
import java.util.List;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/commandrules.yml */
@ConfigSerializable
public class CommandRulesConfig {
    @Comment("Apply command costs, cooldowns, warmups and world rules. The console is never affected.")
    public boolean enabled = true;

    @Comment("Seconds in which a player has to repeat a command to confirm its cost (1-300).")
    public int confirmationSeconds = 15;

    @Comment("Show an action-bar countdown while a command warmup is running.")
    public boolean warmupCountdownActionBar = true;

    @Comment("Cancel a running command warmup when the player changes block.")
    public boolean cancelWarmupOnMove = true;

    @Comment("Cancel a running command warmup when the player takes damage.")
    public boolean cancelWarmupOnDamage = true;

    @Comment("Tell the player when a charged command refused to run and the cost was refunded.\n"
            + "A command that starts but fails internally cannot be detected; use charge-after for that.")
    public boolean refundOnFailureMessage = true;

    @Comment("Charge after a successful dispatch instead of before it. Up front only the balance is\n"
            + "checked, so a command that fails internally is not charged. A withdrawal that fails after\n"
            + "the command ran can no longer be refused; the player and the console are warned instead.")
    public boolean chargeAfter = false;

    @Comment("Rules are matched against the canonical command root, so aliases and namespaced labels\n"
            + "(/tp, /minecraft:tp, /atrium:tp) all hit the same rule. Write the root in 'command';\n"
            + "aliases are resolved with the server command map when the rule is loaded.\n"
            + "  - command: tp\n"
            + "    cost: 100\n"
            + "    exp-levels: 0\n"
            + "    cooldown-seconds: 30\n"
            + "    warmup-seconds: 3\n"
            + "    blocked-worlds: [world_nether]\n"
            + "    permission: ''\n"
            + "    confirm-cost: true\n"
            + "    aliases: []\n"
            + "aliases: extra labels that must hit the same rule when the server command map cannot tell an\n"
            + "  alias from a separate command (for example a plugin alias).\n"
            + "cost = money per use, exp-levels = experience levels per use.\n"
            + "cooldown-seconds: seconds between uses; -1 means once per player; 0 disables the cooldown.\n"
            + "  A player's highest atrium.cmdcooldown.<command>.<seconds> permission overrides it\n"
            + "  (it may raise or lower the configured value; 0 disables the cooldown for that player).\n"
            + "warmup-seconds: delay before the command runs. Moving to another block or taking damage cancels it.\n"
            + "blocked-worlds: world names where the command is refused.\n"
            + "permission: extra permission required in addition to the command's own permission.\n"
            + "  Set it for Brigadier commands whose permission is not exposed through the Bukkit command map.\n"
            + "confirm-cost: ask the player to run the command again within confirmation-seconds before charging.\n"
            + "active: false keeps a rule in the file but ignores it.\n"
            + "Bypasses: atrium.commandrules.bypass and atrium.commandrules.bypass.<command>.")
    public List<Rule> rules = new ArrayList<>();

    @ConfigSerializable
    public static class Rule {
        @Comment("Command root to control, without '/'. Aliases and namespaced forms are resolved to its root.")
        public String command = "";

        @Comment("Extra labels that must hit this rule too, without '/'. Use this when the server\n"
                + "command map cannot tell an alias from a separate command (e.g. a plugin alias).")
        public List<String> aliases = new ArrayList<>();

        @Comment("Rule active. Inactive rules are kept in the file but ignored.")
        public boolean active = true;

        @Comment("Money charged per use (0 = no money cost). Negative values are treated as 0.")
        public double cost = 0;

        @Comment("Experience levels charged per use (0 = no level cost).")
        public int expLevels = 0;

        @Comment("Cooldown in seconds. 0 = no cooldown, -1 = once per player.")
        public int cooldownSeconds = 0;

        @Comment("Warmup in seconds before the command runs. 0 = run immediately.")
        public int warmupSeconds = 0;

        @Comment("Worlds where the command is refused (case-insensitive). Empty = every world.")
        public List<String> blockedWorlds = new ArrayList<>();

        @Comment("Extra permission required before the command runs (empty = none).")
        public String permission = "";

        @Comment("Ask the player to repeat the command to confirm the cost.")
        public boolean confirmCost = false;

        public Rule() {}

        static Rule of(String command, double cost, int expLevels, int cooldownSeconds, int warmupSeconds,
                List<String> blockedWorlds, String permission, boolean confirmCost) {
            return of(command, cost, expLevels, cooldownSeconds, warmupSeconds, blockedWorlds, permission,
                    confirmCost, List.of());
        }

        static Rule of(String command, double cost, int expLevels, int cooldownSeconds, int warmupSeconds,
                List<String> blockedWorlds, String permission, boolean confirmCost, List<String> aliases) {
            Rule rule = new Rule();
            rule.command = command;
            rule.cost = cost;
            rule.expLevels = expLevels;
            rule.cooldownSeconds = cooldownSeconds;
            rule.warmupSeconds = warmupSeconds;
            rule.blockedWorlds = new ArrayList<>(blockedWorlds);
            rule.permission = permission;
            rule.confirmCost = confirmCost;
            rule.aliases = new ArrayList<>(aliases);
            return rule;
        }
    }
}
