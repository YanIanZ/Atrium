package dev.yanianz.atrium.api.actions;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Runs admin-written action lines (published by the {@code actions} module). Used by aliases,
 * interactive commands and other modules. Safe to call from any thread: a sequence always runs on the
 * player's own thread, console commands are dispatched on the global region thread.
 *
 * <h2>Line syntax</h2>
 * <pre>
 *   [condition] [condition] executor: argument
 * </pre>
 * Conditions (prefix {@code !} negates): {@code [perm:node]}, {@code [money:10]} (has at least),
 * {@code [world:name]}, {@code [cooldown:key:30s]} (starts the cooldown when it passes), {@code [chance:25]}.
 * A line with an executor is skipped when a condition fails. A line with <em>only</em> conditions is a guard:
 * when it fails, the sequence stops with {@link ActionResult.Status#CONDITION_FAILED}. Conditions that cannot
 * be evaluated (no player, no economy) fail, also when negated.
 *
 * <p>Executors: {@code console: <cmd>}, {@code player: <cmd>}, {@code msg: <minimessage>},
 * {@code actionbar: <minimessage>}, {@code title: <title>|<subtitle>}, {@code sound: <key> [volume] [pitch]},
 * {@code delay: 2s}, {@code take-money: 10}, {@code give-money: 10}, {@code teleport: world x y z [yaw pitch]},
 * {@code warp: name}, {@code stop} / {@code stop: <minimessage>}. There is deliberately no "as operator" executor.
 * Empty lines and lines starting with {@code #} are ignored.
 *
 * <p>Variables {@code {name}}: {@code {player}}, {@code {uuid}}, {@code {world}}, {@code {x}}, {@code {y}},
 * {@code {z}} plus the caller's map (keys {@code [a-z0-9_]}, built-ins win). In commands every value must be
 * safe (player names {@code [A-Za-z0-9_]{1,16}}, other values letters, digits, space and {@code _.,:+-!?}),
 * otherwise the sequence is rejected. In messages values are inserted as plain text, never parsed.
 * Substitution is a single pass: a value is never substituted again.
 */
public interface ActionService {
    /**
     * Runs the lines for a player ({@code null}: no player context; player-related conditions fail and
     * player executors stop the sequence). The future completes when the sequence ended, including delays.
     */
    CompletableFuture<ActionResult> run(List<String> lines, @Nullable Player player, Map<String, String> variables);

    /**
     * Runs the lines for any command sender: a player is handled like {@link #run}; any other sender (console,
     * command block) runs without player context and receives the {@code msg}/{@code stop} messages.
     */
    CompletableFuture<ActionResult> runAs(CommandSender sender, List<String> lines, Map<String, String> variables);

    /** Syntax check. Returns one human-readable error per broken line ({@code "line 2: ..."}); empty when valid. */
    List<String> validate(List<String> lines);

    default CompletableFuture<ActionResult> run(List<String> lines, Player player) {
        return run(lines, player, Map.of());
    }
}
