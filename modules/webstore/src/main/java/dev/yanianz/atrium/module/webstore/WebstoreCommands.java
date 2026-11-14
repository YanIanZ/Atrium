package dev.yanianz.atrium.module.webstore;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.Map;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /webstore status} and {@code /webstore retry <order_id>}. */
final class WebstoreCommands {
    static final String ADMIN_PERMISSION = "atrium.webstore.admin";

    private final ModuleContext ctx;
    private final DeliveryStore store;

    WebstoreCommands(ModuleContext ctx, DeliveryStore store) {
        this.ctx = ctx;
        this.store = store;
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("webstore", ADMIN_PERMISSION)
                .executes(this::usage)
                .then(Commands.literal("status").executes(this::status))
                .then(Commands.literal("retry")
                        .executes(this::usage)
                        .then(Commands.argument("order_id", StringArgumentType.greedyString()).executes(this::retry)))
                .build(), "Webstore delivery queue: counts by status and retrying failed orders"));
    }

    private int usage(CommandContext<CommandSourceStack> c) {
        ctx.messages().send(c.getSource().getSender(), "webstore.usage");
        return Cmds.OK;
    }

    private int status(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        store.counts().whenComplete((counts, error) -> {
            if (error != null) {
                ctx.logger().error("Could not read webstore delivery counts", error);
                reply(sender, "webstore.storage-error");
                return;
            }
            reply(sender, "webstore.status-header");
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                reply(sender, "webstore.status-entry", Msg.unparsed("status", entry.getKey()),
                        Msg.number("count", entry.getValue()));
            }
        });
        return Cmds.OK;
    }

    private int retry(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String orderId = StringArgumentType.getString(c, "order_id").strip();
        store.retry(orderId).whenComplete((result, error) -> {
            if (error != null) {
                ctx.logger().error("Could not retry webstore order {}", orderId, error);
                reply(sender, "webstore.storage-error");
            } else if (result.retried()) {
                ctx.logger().info("{} requeued webstore order {}", sender.getName(), orderId);
                reply(sender, "webstore.retry-done", Msg.unparsed("order", orderId));
            } else if (result.status() == null) {
                reply(sender, "webstore.retry-not-found", Msg.unparsed("order", orderId));
            } else {
                reply(sender, "webstore.retry-not-failed", Msg.unparsed("order", orderId),
                        Msg.unparsed("status", result.status()));
            }
        });
        return Cmds.OK;
    }

    /** Replies from a storage thread: a player is messaged on their own thread. */
    private void reply(CommandSender sender, String key, TagResolver... tags) {
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, () -> ctx.messages().send(player, key, tags), null);
        } else {
            ctx.messages().send(sender, key, tags);
        }
    }
}
