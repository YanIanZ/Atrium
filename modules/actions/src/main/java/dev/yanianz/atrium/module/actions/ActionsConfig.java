package dev.yanianz.atrium.module.actions;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/**
 * plugins/Atrium/modules/actions.yml
 *
 * <p>Action line syntax (used by aliases, interactive commands and other modules):
 * <pre>
 *   [condition] [condition] executor: argument
 * </pre>
 * Conditions: [perm:node] [money:10] [world:name] [cooldown:key:30s] [chance:25], negated with [!...].
 * Executors: console, player, msg, actionbar, title (title|subtitle), sound (key [volume] [pitch]),
 * delay (2s / 500ms / 40t), take-money, give-money, teleport (world x y z [yaw pitch]), warp, stop.
 * See {@link dev.yanianz.atrium.api.actions.ActionService} for the full description.
 */
@ConfigSerializable
public class ActionsConfig {
    @Comment("Action line syntax:  [condition] [condition] executor: argument\n"
            + "Conditions (prefix ! to negate): [perm:node] [money:10] [world:name] [cooldown:key:30s] [chance:25].\n"
            + "  A line with an executor is skipped when a condition fails; a line with only conditions stops the sequence.\n"
            + "Executors: console: <cmd> | player: <cmd> | msg: <minimessage> | actionbar: <minimessage> |\n"
            + "  title: <title>|<subtitle> | sound: <key> [volume] [pitch] | delay: 2s | take-money: 10 | give-money: 10 |\n"
            + "  teleport: world x y z [yaw pitch] | warp: <name> | stop[: <minimessage>]. There is no 'op:' executor.\n"
            + "Variables: {player} {uuid} {world} {x} {y} {z} plus the caller's ({arg1}, {args}, ...). Values put into\n"
            + "  commands must be plain text (letters, digits, spaces, _.,:+-!?), otherwise the line is rejected.\n\n"
            + "How many sequences (e.g. with delays) may run at the same time for one player.")
    public int maxRunningPerPlayer = 8;

    @Comment("How deep sequences may start other sequences (an alias that runs an alias ...). Stops endless loops.")
    public int maxNestingDepth = 4;

    @Comment("Log every console command run by an action sequence (audit trail).")
    public boolean logConsoleCommands = false;
}
