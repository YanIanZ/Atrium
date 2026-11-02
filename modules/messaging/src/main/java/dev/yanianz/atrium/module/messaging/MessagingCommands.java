package dev.yanianz.atrium.module.messaging;

import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.util.List;

/** Command tree; behaviour lives in {@link MessagingModule}. */
final class MessagingCommands {
    private final MessagingModule module;
    private final ModuleContext ctx;

    MessagingCommands(MessagingModule module, ModuleContext ctx) {
        this.module = module;
        this.ctx = ctx;
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("actionbarmsg", MessagingModule.PERM_ACTIONBAR)
                .executes(module::helpBar)
                .then(Cmds.literal("all")
                        .executes(module::helpBar)
                        .then(Commands.argument("message", greedyString())
                                .executes(module::actionBarAll)))
                .then(Commands.argument("target", ArgumentTypes.player())
                        .then(Commands.argument("message", greedyString())
                                .executes(module::actionBarTarget)))
                .build(), "Show a MiniMessage action bar to a player or everyone"));

        ctx.command(CommandSpec.of(Cmds.literal("ctellraw", MessagingModule.PERM_CTELLRAW)
                .executes(module::helpTellraw)
                .then(Cmds.literal("all")
                        .executes(module::helpTellraw)
                        .then(Commands.argument("message", greedyString())
                                .executes(module::tellrawAll)))
                .then(Commands.argument("target", ArgumentTypes.player())
                        .then(Commands.argument("message", greedyString())
                                .executes(module::tellrawTarget)))
                .build(), "Send a raw MiniMessage to a player or everyone"));

        ctx.command(CommandSpec.of(Cmds.literal("helpop", MessagingModule.PERM_HELPOP)
                .executes(module::helpHelpOp)
                .then(Commands.argument("message", greedyString())
                        .executes(module::helpOp))
                .build(), "Ask online staff for help"));

        ctx.command(CommandSpec.of(Cmds.literal("alert", MessagingModule.PERM_ALERT)
                .executes(module::helpAlert)
                .then(Cmds.literal("add")
                        .executes(module::helpAlert)
                        .then(Commands.argument("player", word())
                                .suggests(Cmds.knownPlayers(ctx.users()))
                                .then(Commands.argument("reason", greedyString())
                                        .executes(module::alertAdd))))
                .then(Cmds.literal("remove")
                        .executes(module::helpAlert)
                        .then(Commands.argument("player", word())
                                .suggests(Cmds.knownPlayers(ctx.users()))
                                .executes(module::alertRemove)))
                .then(Cmds.literal("list")
                        .executes(module::alertList)
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .suggests(Cmds.suggest(() -> List.of("1", "2", "3", "4", "5")))
                                .executes(module::alertList)))
                .build(), "Manage staff alerts shown when a player joins"));

        ctx.command(CommandSpec.of(Cmds.literal("colorpicker", MessagingModule.PERM_COLORPICKER)
                .executes(module::colorPicker)
                .build(), "Clickable colour palette that inserts MiniMessage tags into chat"));
        ctx.command(CommandSpec.of(Cmds.literal("colorlimits", MessagingModule.PERM_COLORLIMITS)
                .executes(module::colorLimits)
                .build(), "Show your colour and format permissions for chat, nick, signs and anvils"));
    }
}
