package dev.yanianz.atrium.module.discord;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

/** {@code /discord status} and {@code /discord test <channel>}. */
final class DiscordCommands {
    private final ModuleContext ctx;
    private final Cmds cmds;
    private final DiscordRelay relay;

    DiscordCommands(ModuleContext ctx, DiscordRelay relay) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.relay = relay;
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("discord")
                .executes(this::usage)
                .then(Commands.literal("status")
                        .requires(Cmds.permission("atrium.discord.status"))
                        .executes(this::status))
                .then(Commands.literal("test")
                        .requires(Cmds.permission("atrium.discord.test"))
                        .executes(this::usage)
                        .then(Commands.argument("channel", StringArgumentType.word())
                                .suggests(Cmds.suggest(DiscordChannel::keys))
                                .executes(this::test)))
                .build(), "Discord webhook relay status and test messages"));
    }

    private int usage(CommandContext<CommandSourceStack> context) {
        ctx.messages().send(context.getSource().getSender(), "discord.usage-discord");
        return Cmds.OK;
    }

    private int status(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!relay.anyConfigured()) {
            ctx.messages().send(sender, "discord.status-empty");
            return Cmds.OK;
        }
        ctx.messages().send(sender, "discord.status-header");
        for (DiscordRelay.ChannelStatus entry : relay.status()) {
            WebhookQueue.Stats stats = entry.stats();
            ctx.messages().send(sender, "discord.status-entry",
                    Msg.unparsed("channel", entry.channel().key()),
                    Msg.component("state", state(sender, entry)),
                    Msg.number("queued", stats == null ? 0 : stats.queued()),
                    Msg.number("capacity", stats == null ? 0 : stats.capacity()),
                    Msg.number("sent", stats == null ? 0 : stats.sent()),
                    Msg.number("dropped", stats == null ? 0 : stats.dropped()),
                    Msg.number("failed", stats == null ? 0 : stats.failed()),
                    Msg.number("retries", stats == null ? 0 : stats.retries()),
                    Msg.unparsed("error", stats == null || stats.lastError() == null ? "-" : stats.lastError()));
        }
        return Cmds.OK;
    }

    private Component state(CommandSender sender, DiscordRelay.ChannelStatus entry) {
        if (entry.stats() != null) {
            return ctx.messages().render(sender, "discord.state-configured",
                    Msg.unparsed("mask", entry.mask() == null ? "webhook#?" : entry.mask()));
        }
        if (entry.invalidReason() != null) {
            return ctx.messages().render(sender, "discord.state-invalid",
                    Msg.unparsed("reason", entry.invalidReason()));
        }
        return ctx.messages().render(sender, "discord.state-missing");
    }

    private int test(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String raw = StringArgumentType.getString(context, "channel");
        DiscordChannel channel = DiscordChannel.byKey(raw)
                .orElseThrow(() -> cmds.fail(context.getSource(), "discord.unknown-channel",
                        Msg.unparsed("channel", raw), Msg.unparsed("channels", String.join(", ", DiscordChannel.keys()))));
        CommandSender sender = context.getSource().getSender();
        DiscordRelay.TestResult result = relay.test(channel);
        switch (result) {
            case MISSING -> ctx.messages().send(sender, "discord.no-webhook",
                    Msg.unparsed("channel", channel.key()));
            case INVALID -> {
                String reason = relay.invalidReason(channel);
                ctx.messages().send(sender, "discord.invalid-webhook",
                        Msg.unparsed("channel", channel.key()),
                        Msg.unparsed("reason", reason == null ? "invalid URL" : reason));
            }
            case QUEUED -> ctx.messages().send(sender, "discord.test-queued",
                    Msg.unparsed("channel", channel.key()),
                    Msg.unparsed("mask", relay.mask(channel) == null ? "webhook#?" : relay.mask(channel)));
        }
        return Cmds.OK;
    }
}
