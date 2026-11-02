package dev.yanianz.atrium.module.mail;

import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.Commands;

/** Command tree; the behaviour lives in {@link MailModule}. */
final class MailCommands {
    private final MailModule module;
    private final ModuleContext ctx;

    MailCommands(MailModule module, ModuleContext ctx) {
        this.module = module;
        this.ctx = ctx;
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("mail", "atrium.mail")
                .executes(module::help)
                .then(Cmds.literal("send", "atrium.mail.send")
                        .executes(context -> module.usage(context, "mail.usage-send"))
                        .then(Commands.argument("player", word())
                                .suggests(Cmds.knownPlayers(ctx.users()))
                                .executes(context -> module.usage(context, "mail.usage-send"))
                                .then(Commands.argument("message", greedyString())
                                        .executes(module::send))))
                .then(Cmds.literal("read", "atrium.mail.read")
                        .executes(module::read)
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(module::read)))
                .then(Cmds.literal("clear", "atrium.mail.clear")
                        .executes(module::clearAll)
                        .then(Cmds.literal("all")
                                .executes(module::clearAll))
                        .then(Commands.argument("id", LongArgumentType.longArg(1))
                                .executes(module::clearOne)))
                .then(Cmds.literal("sendall", "atrium.mail.sendall")
                        .executes(context -> module.usage(context, "mail.usage-sendall"))
                        .then(Commands.argument("message", greedyString())
                                .executes(module::sendAll)))
                .then(Cmds.literal("sendtemp", "atrium.mail.sendtemp")
                        .executes(context -> module.usage(context, "mail.usage-sendtemp"))
                        .then(Commands.argument("player", word())
                                .suggests(Cmds.knownPlayers(ctx.users()))
                                .executes(context -> module.usage(context, "mail.usage-sendtemp"))
                                .then(Commands.argument("duration", word())
                                        .executes(context -> module.usage(context, "mail.usage-sendtemp"))
                                        .then(Commands.argument("message", greedyString())
                                                .executes(module::sendTemp)))))
                .build(), "Send and read offline mail"));
    }
}
