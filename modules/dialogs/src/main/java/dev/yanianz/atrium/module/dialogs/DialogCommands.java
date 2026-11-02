package dev.yanianz.atrium.module.dialogs;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /dialog open <name> [player]}, {@code /dialog preview <name>}, {@code /dialog list},
 * {@code /dialog reload}. There is deliberately no way to pass variables on the command line: dialog variables only
 * come from validated inputs of the click itself. Registering {@code /dialog} shadows the vanilla command of the
 * same name; it stays reachable as {@code /minecraft:dialog}.
 */
final class DialogCommands implements TaskHandle {
    private final DialogsModule module;
    private final ModuleContext ctx;
    private final Cmds cmds;
    private volatile boolean cancelled;

    DialogCommands(DialogsModule module) {
        this.module = module;
        this.ctx = module.context();
        this.cmds = ctx.commands();
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("dialog")
                .requires(source -> source.getSender().hasPermission(DialogsModule.PERM_OPEN)
                        || source.getSender().hasPermission(DialogsModule.PERM_LIST)
                        || source.getSender().hasPermission(DialogsModule.PERM_RELOAD)
                        || source.getSender().hasPermission(DialogsModule.PERM_PREVIEW))
                .executes(this::usage)
                .then(Cmds.literal("open", DialogsModule.PERM_OPEN)
                        .executes(this::usage)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((c, builder) -> {
                                    String typed = builder.getRemainingLowerCase();
                                    for (String name : visible(c.getSource().getSender())) {
                                        if (name.startsWith(typed)) {
                                            builder.suggest(name);
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(this::open)
                                .then(Commands.argument("target", ArgumentTypes.player()).executes(this::open))))
                .then(Cmds.literal("preview", DialogsModule.PERM_PREVIEW)
                        .executes(this::usage)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(Cmds.suggest(() -> module.dialogs().keySet()))
                                .executes(this::preview)))
                .then(Cmds.literal("list", DialogsModule.PERM_LIST).executes(this::list))
                .then(Cmds.literal("reload", DialogsModule.PERM_RELOAD).executes(this::reload))
                .build(), "Open, preview, list or reload admin-defined dialogs", "dialogs"));
    }

    private int usage(CommandContext<CommandSourceStack> c) {
        ctx.messages().send(c.getSource().getSender(), "dialogs.usage");
        return Cmds.OK;
    }

    private int open(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "name").toLowerCase(Locale.ROOT);
        DialogDefinition definition = module.dialog(name).orElse(null);
        if (definition == null) {
            throw cmds.fail(c.getSource(), "dialogs.unknown", Msg.unparsed("name", name));
        }
        // The dialog permission is checked on whoever causes the dialog to open.
        if (!definition.permission().isEmpty() && !sender.hasPermission(definition.permission())) {
            throw cmds.fail(c.getSource(), "dialogs.no-permission-dialog");
        }
        Player target = cmds.selfOrOther(c, "target", DialogsModule.PERM_OPEN_OTHERS);
        ctx.scheduler().entity(target, () -> {
            if (cancelled) {
                return;
            }
            boolean opened = module.openEntry(target, definition);
            if (opened && !target.equals(sender)) {
                reply(sender, "opened-other", Msg.unparsed("name", definition.id()),
                        Msg.unparsed("player", target.getName()));
            }
        }, () -> reply(sender, "target-left"));
        return Cmds.OK;
    }

    private int preview(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        Player viewer = cmds.player(c.getSource());
        String name = StringArgumentType.getString(c, "name").toLowerCase(Locale.ROOT);
        DialogDefinition definition = module.dialog(name).orElse(null);
        if (definition == null) {
            throw cmds.fail(c.getSource(), "dialogs.unknown", Msg.unparsed("name", name));
        }
        ctx.scheduler().entity(viewer, () -> {
            if (!cancelled) {
                module.preview(viewer, definition);
            }
        }, () -> reply(sender, "target-left"));
        return Cmds.OK;
    }

    private int list(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        List<String> names = visible(sender);
        if (names.isEmpty()) {
            ctx.messages().send(sender, "dialogs.list-empty");
        } else {
            ctx.messages().send(sender, "dialogs.list", Msg.number("count", names.size()),
                    Msg.unparsed("dialogs", String.join(", ", names)));
        }
        return Cmds.OK;
    }

    private int reload(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        module.reloadAsync().whenComplete((result, error) -> {
            if (cancelled) {
                return;
            }
            if (error != null) {
                ctx.logger().error("Reloading dialogs failed", error);
                reply(sender, "reload-failed");
                return;
            }
            reply(sender, "reloaded", Msg.number("count", result.dialogs().size()),
                    Msg.number("failed", result.failedFiles()));
        });
        return Cmds.OK;
    }

    private List<String> visible(CommandSender sender) {
        return module.dialogs().values().stream()
                .filter(dialog -> dialog.permission().isEmpty() || sender.hasPermission(dialog.permission()))
                .map(DialogDefinition::id)
                .toList();
    }

    private void reply(CommandSender recipient, String key, TagResolver... tags) {
        if (cancelled) {
            return;
        }
        if (recipient instanceof Player player) {
            ctx.scheduler().entity(player, () -> {
                if (!cancelled) {
                    ctx.messages().send(player, "dialogs." + key, tags);
                }
            }, null);
        } else {
            ctx.messages().send(recipient, "dialogs." + key, tags);
        }
    }

    @Override
    public void cancel() {
        cancelled = true;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }
}
