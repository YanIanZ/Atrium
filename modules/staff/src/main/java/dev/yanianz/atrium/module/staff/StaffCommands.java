package dev.yanianz.atrium.module.staff;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.util.Set;
import java.util.TreeSet;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;

/** {@code /staffmode}, {@code /sudo}, {@code /commandspy} and {@code /spectate}. */
final class StaffCommands {
    private static final java.util.List<String> RECOVER_ACTIONS = java.util.List.of("list", "apply", "discard");
    private final ModuleContext ctx;
    private final Cmds cmds;
    private final StaffManager manager;
    private final SpectateManager spectate;
    private final StaffStore store;

    StaffCommands(ModuleContext ctx, StaffManager manager, SpectateManager spectate, StaffStore store) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.manager = manager;
        this.spectate = spectate;
        this.store = store;
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("staffmode", "atrium.staff.mode")
                .executes(this::staffMode)
                .then(Cmds.literal("recover", StaffManager.PERMISSION_RECOVER)
                        .executes(this::recoverOverview)
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(Cmds.knownPlayers(ctx.users()))
                                .executes(c -> recover(c, "list"))
                                .then(Commands.argument("action", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            for (String action : RECOVER_ACTIONS) {
                                                if (action.startsWith(builder.getRemainingLowerCase())) {
                                                    builder.suggest(action);
                                                }
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(c -> recover(c, StringArgumentType.getString(c, "action"))))))
                .build(), "Enter or leave staff mode with a saved inventory, tools and god mode", "staff"));
        ctx.command(CommandSpec.of(Cmds.literal("sudo", "atrium.sudo")
                .executes(this::sudoUsage)
                .then(Commands.argument("player", ArgumentTypes.player())
                        .executes(this::sudoUsage)
                        .then(Commands.argument("command", StringArgumentType.greedyString())
                                .suggests(commandNames())
                                .executes(this::sudo)))
                .build(), "Run a command or send a chat message as another player"));
        ctx.command(CommandSpec.of(Cmds.literal("commandspy", "atrium.commandspy")
                .executes(this::commandSpy)
                .build(), "Toggle seeing the commands other players run", "cspy"));
        ctx.command(CommandSpec.of(Cmds.literal("spectate", "atrium.spectate")
                .executes(this::stopSpectating)
                .then(Commands.argument("player", ArgumentTypes.player()).executes(this::spectate))
                .build(), "Spectate a player, or return to your previous location with /spectate"));
    }

    private int staffMode(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        manager.toggle(player, c.getSource().getSender());
        return Cmds.OK;
    }

    private int recoverOverview(CommandContext<CommandSourceStack> c) {
        manager.recoverOverview(c.getSource().getSender());
        return Cmds.OK;
    }

    private int recover(CommandContext<CommandSourceStack> c, String rawAction) throws CommandSyntaxException {
        String action = rawAction.toLowerCase(java.util.Locale.ROOT);
        if (!RECOVER_ACTIONS.contains(action)) {
            throw cmds.fail(c.getSource(), "staff.usage.recover");
        }
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        // Only accounts that joined before can have a snapshot: no profile lookup for unknown names.
        ctx.users().resolveAccount(name, false).whenComplete((ref, error) -> {
            if (error != null) {
                ctx.logger().error("Resolving {} for /staffmode recover failed", name, error);
                reply(sender, "staff.recover-failed");
                return;
            }
            if (ref.isEmpty()) {
                reply(sender, "staff.recover-empty", Msg.unparsed("player", name));
                return;
            }
            manager.recover(sender, ref.get().uuid(), ref.get().name(), action);
        });
        return Cmds.OK;
    }

    private int sudoUsage(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        throw cmds.fail(c.getSource(), "staff.usage.sudo");
    }

    private int sudo(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        Player target = cmds.argPlayer(c, "player");
        String raw = StringArgumentType.getString(c, "command").strip();
        if (raw.isEmpty()) {
            throw cmds.fail(c.getSource(), "staff.usage.sudo");
        }
        boolean console = sender instanceof ConsoleCommandSender;
        SudoRules.Decision first = SudoRules.decide(console, true, target.isOp(),
                target.hasPermission("atrium.sudo.exempt"));
        refuseIfNeeded(c.getSource(), first, target.getName());

        boolean chat = raw.regionMatches(true, 0, "c:", 0, 2);
        String payload = chat ? raw.substring(2).strip() : raw.startsWith("/") ? raw.substring(1) : raw;
        if (payload.isBlank()) {
            throw cmds.fail(c.getSource(), "staff.usage.sudo");
        }
        String senderName = sender.getName();
        String targetName = target.getName();
        ctx.scheduler().entity(target, () -> {
            // Checked again on the target's own thread: op/exempt may have changed since the command was typed.
            SudoRules.Decision decision = SudoRules.decide(console, target.isOnline(), target.isOp(),
                    target.hasPermission("atrium.sudo.exempt"));
            if (decision != SudoRules.Decision.ALLOWED) {
                reply(sender, refusalKey(decision), Msg.unparsed("player", targetName));
                return;
            }
            try {
                if (chat) {
                    ctx.logger().info("{} made {} say: {}", senderName, targetName, payload);
                    target.chat(payload);
                    reply(sender, "staff.sudo-chat-done", Msg.unparsed("player", targetName),
                            Msg.unparsed("message", payload));
                } else {
                    ctx.logger().info("{} made {} run: /{}", senderName, targetName, payload);
                    if (target.performCommand(payload)) {
                        reply(sender, "staff.sudo-done", Msg.unparsed("player", targetName),
                                Msg.unparsed("command", "/" + payload));
                    } else {
                        reply(sender, "staff.sudo-failed", Msg.unparsed("player", targetName));
                    }
                }
            } catch (RuntimeException e) {
                ctx.logger().error("Sudo of {} by {} failed", targetName, senderName, e);
                reply(sender, "staff.sudo-failed", Msg.unparsed("player", targetName));
            }
        }, () -> reply(sender, "staff.sudo-failed", Msg.unparsed("player", targetName)));

        return Cmds.OK;
    }

    private void refuseIfNeeded(CommandSourceStack source, SudoRules.Decision decision, String targetName)
            throws CommandSyntaxException {
        if (decision != SudoRules.Decision.ALLOWED) {
            throw cmds.fail(source, refusalKey(decision), Msg.unparsed("player", targetName));
        }
    }

    private static String refusalKey(SudoRules.Decision decision) {
        return switch (decision) {
            case TARGET_EXEMPT -> "staff.sudo-exempt";
            case TARGET_OPERATOR -> "staff.sudo-op";
            case TARGET_NOT_PLAYER -> "staff.sudo-not-player";
            case ALLOWED -> "staff.sudo-failed";
        };
    }

    private int commandSpy(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        StaffPreferences current = ctx.users().user(player).get(store.preferences);
        StaffPreferences next = current.withCommandSpy(!current.commandSpy());
        ctx.users().user(player).update(store.preferences, ignored -> next);
        ctx.users().saveNow(player.getUniqueId()).whenComplete((ignored, error) -> {
            if (error != null) {
                ctx.logger().error("Saving the command spy toggle of {} failed", player.getName(), error);
                ctx.scheduler().entity(player, () -> ctx.messages().send(player, "staff.commandspy-save-failed"),
                        null);
                return;
            }
            ctx.scheduler().entity(player, () -> ctx.messages().send(player, next.commandSpy()
                    ? "staff.commandspy-enabled" : "staff.commandspy-disabled"), null);
        });
        return Cmds.OK;
    }

    private int spectate(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player viewer = cmds.player(c.getSource());
        Player target = cmds.argPlayer(c, "player");
        spectate.start(viewer, target);
        return Cmds.OK;
    }

    private int stopSpectating(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        spectate.stop(cmds.player(c.getSource()));
        return Cmds.OK;
    }

    private SuggestionProvider<CommandSourceStack> commandNames() {
        return (context, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            if (!remaining.contains(" ")) {
                Set<String> names = new TreeSet<>();
                for (String name : org.bukkit.Bukkit.getCommandMap().getKnownCommands().keySet()) {
                    if (!name.contains(":")) {
                        names.add(name);
                    }
                }
                for (String name : names) {
                    if (name.startsWith(remaining)) {
                        builder.suggest(name);
                    }
                }
                if ("c:".startsWith(remaining)) {
                    builder.suggest("c:");
                }
            }
            return builder.buildFuture();
        };
    }

    private void reply(CommandSender sender, String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... tags) {
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, () -> ctx.messages().send(player, key, tags), null);
        } else {
            ctx.messages().send(sender, key, tags);
        }
    }

    static void registerPermissions(ModuleContext ctx) {
        ctx.permission("atrium.staff.mode", PermissionDefault.OP,
                "Enter staff mode with a saved inventory, staff tools, vanish and god mode");
        ctx.permission(StaffManager.PERMISSION_RECOVER, PermissionDefault.OP,
                "Review, apply or discard staff snapshots held after an interrupted restore");
        ctx.permission("atrium.staff.freeze", PermissionDefault.OP, "Freeze players with the staff freeze tool");
        ctx.permission("atrium.sudo", PermissionDefault.OP, "Run commands and chat as another player");
        ctx.permission("atrium.sudo.exempt", PermissionDefault.FALSE, "Refuse being controlled by /sudo");
        ctx.permission("atrium.commandspy", PermissionDefault.OP, "See the commands other players run");
        ctx.permission("atrium.commandspy.exempt", PermissionDefault.FALSE, "Hide your commands from command spy");
        ctx.permission("atrium.spectate", PermissionDefault.OP, "Spectate players and return afterwards");
    }
}
