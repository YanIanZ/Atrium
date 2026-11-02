package dev.yanianz.atrium.module.chat;

import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;

import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.Commands;

/** Command tree; every method delegates to {@link ChatModule} so the logic stays in one place. */
final class ChatCommands {
    private final ChatModule module;
    private final ModuleContext ctx;

    ChatCommands(ChatModule module) {
        this.module = module;
        this.ctx = module.context();
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("msg", "atrium.chat.msg")
                .executes(context -> module.usage(context, "chat.usage-msg"))
                .then(Commands.argument("target", word())
                        .suggests(Cmds.suggest(module::messageTargets))
                        .executes(context -> module.usage(context, "chat.usage-msg"))
                        .then(Commands.argument("message", greedyString())
                                .executes(module::msg)))
                .build(), "Send a private message", "tell", "w"));

        ctx.command(CommandSpec.of(Cmds.literal("reply", "atrium.chat.reply")
                .executes(context -> module.usage(context, "chat.usage-reply"))
                .then(Commands.argument("message", greedyString())
                        .executes(module::reply))
                .build(), "Reply to your last private message", "r"));

        ctx.command(CommandSpec.of(Cmds.literal("ignore", "atrium.chat.ignore")
                .executes(context -> module.usage(context, "chat.usage-ignore"))
                .then(Cmds.literal("list")
                        .executes(module::ignoreList))
                .then(Commands.argument("player", word())
                        .suggests(Cmds.suggest(() -> ctx.users().knownNames("", 100)))
                        .executes(module::ignore))
                .build(), "Ignore or unignore a player"));

        ctx.command(CommandSpec.of(Cmds.literal("msgtoggle", "atrium.chat.msgtoggle")
                .executes(module::msgToggle)
                .build(), "Toggle incoming private messages"));

        ctx.command(CommandSpec.of(Cmds.literal("socialspy", "atrium.chat.socialspy")
                .executes(module::socialSpy)
                .build(), "Toggle seeing private messages between players"));

        ctx.command(CommandSpec.of(Cmds.literal("staffchat", "atrium.chat.staff")
                .executes(module::staffChat)
                .then(Commands.argument("message", greedyString())
                        .executes(module::staffChatMessage))
                .build(), "Toggle staff chat or send a staff message", "sc"));

        ctx.command(CommandSpec.of(Cmds.literal("broadcast", "atrium.chat.broadcast")
                .executes(context -> module.usage(context, "chat.usage-broadcast"))
                .then(Commands.argument("message", greedyString())
                        .executes(module::broadcast))
                .build(), "Broadcast a message to the server", "bc"));

        ctx.command(CommandSpec.of(Cmds.literal("me", "atrium.chat.me")
                .executes(context -> module.usage(context, "chat.usage-me"))
                .then(Commands.argument("action", greedyString())
                        .executes(module::me))
                .build(), "Describe an action"));

        ctx.command(CommandSpec.of(Cmds.literal("chatcolor", "atrium.chatcolor")
                .executes(module::chatColorInfo)
                .then(Cmds.literal("list")
                        .executes(module::chatColorList))
                .then(Cmds.literal("reset")
                        .executes(module::chatColorReset))
                .then(Cmds.literal("color")
                        .then(Commands.argument("name", word())
                                .suggests(Cmds.suggest(() -> module.chatColorNames(false)))
                                .executes(context -> module.chatColorSet(context, "name", false))))
                .then(Cmds.literal("gradient")
                        .then(Commands.argument("name", word())
                                .suggests(Cmds.suggest(() -> module.chatColorNames(true)))
                                .executes(context -> module.chatColorSet(context, "name", true))))
                .then(Commands.argument("name", word())
                        .suggests(Cmds.suggest(() -> module.chatColorNames(null)))
                        .executes(context -> module.chatColorSet(context, "name", null)))
                .build(), "Choose or reset your default chat colour"));

        ctx.command(CommandSpec.of(Cmds.literal("emoji", "atrium.chat.emoji")
                .executes(module::emojiList)
                .build(), "List clickable chat emoji shortcodes", "emojis"));

        ctx.command(CommandSpec.of(Cmds.literal("clearchat", "atrium.chat.clearchat")
                .executes(context -> module.clearChat(context, false))
                .then(Cmds.literal("self")
                        .executes(context -> module.clearChat(context, true)))
                .build(), "Clear chat for everyone, or only yourself"));
    }
}
