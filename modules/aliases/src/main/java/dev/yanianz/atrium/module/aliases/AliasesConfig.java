package dev.yanianz.atrium.module.aliases;

import java.util.ArrayList;
import java.util.List;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/aliases.yml */
@ConfigSerializable
public class AliasesConfig {
    @Comment("Custom commands. They are registered as real commands when the server starts: adding or removing a\n"
            + "command, or changing its name, aliases, arguments, description or permission default needs a server restart.\n"
            + "Actions, permission node, player-only, usage and suggestions are applied by /atrium reload.\n"
            + "Actions use the action-line syntax described in modules/actions.yml. Variables: {arg1}..{argN},\n"
            + "{<argument name>} and {args} (all arguments joined with spaces), plus {player} {uuid} {world} {x} {y} {z}.\n"
            + "Argument types: word, player, integer, number, text (rest of the line; only as the last argument).\n"
            + "Values of word/player/integer/number arguments are checked before the actions run; text values may only be\n"
            + "used in console:/player: lines when they contain plain characters (letters, digits, spaces, _.,:+-!?).")
    public List<AliasDefinition> commands = new ArrayList<>(List.of(
            AliasDefinition.of("discord", List.of("dc"), "Shows the Discord invite", "true", false, List.of(),
                    List.of("msg: <prefix><gray>Join our Discord: <aqua><click:open_url:'https://discord.gg/example'>discord.gg/example</click>")),
            AliasDefinition.of("gift", List.of(), "Gives a player a cake", "op", false,
                    List.of(ArgumentDefinition.of("target", "player", true)),
                    List.of("console: give {target} cake 1", "msg: <prefix><green>Sent a cake to <white>{target}</white>.")),
            AliasDefinition.of("buybread", List.of(), "Buy 16 bread for 25", "true", true, List.of(),
                    List.of("[cooldown:buybread:10s]", "take-money: 25", "console: give {player} bread 16",
                            "msg: <prefix><green>You bought 16 bread."))));

    @Comment("Command replacement: when a player types a command starting with 'from', it is rewritten to 'to' (the\n"
            + "rest of the line is kept). The longest matching 'from' wins; a rewritten command is not rewritten again.\n"
            + "'permission' (optional) limits the rule to players who have it; others run the command unchanged.\n"
            + "Example:  - from: \"day\"\n            to: \"time set day\"\n            permission: \"\"")
    public List<ReplacementRule> replacements = new ArrayList<>();

    @ConfigSerializable
    public static class AliasDefinition {
        @Comment("Command name: lower-case letters, digits, _ and -.")
        public String name = "";

        @Comment("Extra names for the command.")
        public List<String> aliases = new ArrayList<>();

        @Comment("Shown in /help.")
        public String description = "";

        @Comment("Permission needed to see and run the command. Empty: atrium.alias.<name>.")
        public String permission = "";

        @Comment("Who has the permission by default: true (everyone), op, not_op or false.")
        public String permissionDefault = "true";

        @Comment("Only players may run it. When false the console can run it too (player conditions then fail).")
        public boolean playerOnly = true;

        @Comment("Usage text shown when required arguments are missing (MiniMessage). Empty: generated from the arguments.")
        public String usage = "";

        @Comment("Arguments in order. Required arguments must come before optional ones.")
        public List<ArgumentDefinition> arguments = new ArrayList<>();

        @Comment("Action lines run when the command is used.")
        public List<String> actions = new ArrayList<>();

        public AliasDefinition() {}

        static AliasDefinition of(String name, List<String> aliases, String description, String permissionDefault,
                boolean playerOnly, List<ArgumentDefinition> arguments, List<String> actions) {
            AliasDefinition d = new AliasDefinition();
            d.name = name;
            d.aliases = new ArrayList<>(aliases);
            d.description = description;
            d.permissionDefault = permissionDefault;
            d.playerOnly = playerOnly;
            d.arguments = new ArrayList<>(arguments);
            d.actions = new ArrayList<>(actions);
            return d;
        }
    }

    @ConfigSerializable
    public static class ArgumentDefinition {
        @Comment("Variable name ({name}); lower-case letters, digits and _.")
        public String name = "";

        @Comment("word, player, integer, number or text.")
        public String type = "word";

        @Comment("Whether the argument must be given.")
        public boolean required = true;

        @Comment("Tab-completion values (player arguments suggest online players when empty).")
        public List<String> suggestions = new ArrayList<>();

        public ArgumentDefinition() {}

        static ArgumentDefinition of(String name, String type, boolean required) {
            ArgumentDefinition d = new ArgumentDefinition();
            d.name = name;
            d.type = type;
            d.required = required;
            return d;
        }
    }

    @ConfigSerializable
    public static class ReplacementRule {
        @Comment("Typed command prefix, without '/'.")
        public String from = "";

        @Comment("Replacement, without '/'.")
        public String to = "";

        @Comment("Only applied for players with this permission. Empty: everyone.")
        public String permission = "";
    }
}
