package dev.yanianz.atrium.api.chat;

import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Optional moderation hook used by the chat module.
 *
 * <p>A moderation module (mute, jail, shadow mute, ...) can implement this and publish it with its
 * {@code ModuleContext}: {@code ctx.service(ChatGate.class, gate)}. The chat module looks the service up
 * for every message, so it also works when the moderation module enables after chat.
 *
 * <p>Return a non-empty {@link Optional} to stop the message and show that reason to the sender. The
 * interface deliberately only describes a hard denial; a shadow mute is better implemented by letting
 * the message through and replacing the outgoing component before delivery.
 */
public interface ChatGate {
    Optional<Component> denyReason(Player sender, ChatChannel channel);
}
