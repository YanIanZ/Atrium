package dev.yanianz.atrium.module.combat;

import dev.yanianz.atrium.core.module.ModuleContext;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Sends a message on the recipient's own thread, never from a global or async callback. */
final class Reply {
    private Reply() {}

    static void send(ModuleContext ctx, CommandSender recipient, String key, TagResolver... tags) {
        if (recipient instanceof Player player) {
            ctx.scheduler().entity(player, () -> ctx.messages().send(player, key, tags), null);
        } else {
            ctx.messages().send(recipient, key, tags);
        }
    }
}
