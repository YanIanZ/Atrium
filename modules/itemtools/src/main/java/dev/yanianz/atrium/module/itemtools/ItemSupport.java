package dev.yanianz.atrium.module.itemtools;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Shared plumbing for the item commands: every reply that touches a player runs on that player's own thread
 * and every unexpected error is logged with context before the player sees a generic failure message.
 * {@link #cancelled()} turns false when the module is disabled, so no queued reply survives a disable.
 */
final class ItemSupport {
    private final ModuleContext ctx;
    private final Cmds cmds;
    private final AtomicBoolean cancelled;

    ItemSupport(ModuleContext ctx, AtomicBoolean cancelled) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.cancelled = cancelled;
    }

    ModuleContext ctx() {
        return ctx;
    }

    Cmds cmds() {
        return cmds;
    }

    boolean cancelled() {
        return cancelled.get();
    }

    void register(LiteralArgumentBuilder<CommandSourceStack> node, String description) {
        ctx.command(CommandSpec.of(node.build(), description));
    }

    int usage(CommandContext<CommandSourceStack> c, String key) throws CommandSyntaxException {
        throw cmds.fail(c.getSource(), "itemtools." + key);
    }

    void onPlayer(Player player, Runnable action) {
        ctx.scheduler().entity(player, () -> {
            if (cancelled.get()) {
                return;
            }
            try {
                action.run();
            } catch (RuntimeException e) {
                ctx.logger().error("Item command failed for {}", player.getUniqueId(), e);
                replyNow(player, "failed");
            }
        }, null);
    }

    void reply(CommandSender sender, String key, TagResolver... tags) {
        if (cancelled.get()) {
            return;
        }
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, () -> {
                if (!cancelled.get()) {
                    ctx.messages().send(player, "itemtools." + key, tags);
                }
            }, null);
        } else {
            ctx.messages().send(sender, "itemtools." + key, tags);
        }
    }

    void replyNow(Player player, String key, TagResolver... tags) {
        if (!cancelled.get()) {
            ctx.messages().send(player, "itemtools." + key, tags);
        }
    }

    /** Logs a failure with context and tells the player only that the command failed. */
    void failed(Player player, String what, Throwable error) {
        ctx.logger().error("{} failed for {}", what, player.getUniqueId(), error);
        reply(player, "failed");
    }

    void failed(CommandSender sender, String what, Throwable error) {
        ctx.logger().error("{} failed for {}", what, sender, error);
        reply(sender, "failed");
    }
}
