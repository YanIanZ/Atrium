package dev.yanianz.atrium.module.chat;

import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Immutable per-player chat state. A missing reply target is {@code null}; the staff-chat toggle and the
 * default chat colour are persisted so a restart does not silently drop them.
 */
record ChatUser(Set<UUID> ignored, @Nullable UUID replyTo, @Nullable String replyToName, boolean messagesEnabled,
        boolean socialSpy, boolean staffChat, @Nullable String chatColor) {
    static final ChatUser DEFAULT = new ChatUser(Set.of(), null, null, true, false, false, null);

    ChatUser {
        ignored = Set.copyOf(ignored);
        replyToName = replyToName == null || replyToName.isBlank() ? null : replyToName;
        chatColor = chatColor == null || chatColor.isBlank() ? null : chatColor;
    }

    boolean ignores(UUID uuid) {
        return ignored.contains(uuid);
    }

    ChatUser withIgnored(UUID uuid, boolean ignore) {
        Set<UUID> next = new java.util.HashSet<>(ignored);
        if (ignore) {
            next.add(uuid);
        } else {
            next.remove(uuid);
        }
        return new ChatUser(next, replyTo, replyToName, messagesEnabled, socialSpy, staffChat, chatColor);
    }

    ChatUser withReplyTo(@Nullable UUID uuid, @Nullable String name) {
        return new ChatUser(ignored, uuid, name, messagesEnabled, socialSpy, staffChat, chatColor);
    }

    ChatUser withMessagesEnabled(boolean enabled) {
        return new ChatUser(ignored, replyTo, replyToName, enabled, socialSpy, staffChat, chatColor);
    }

    ChatUser withSocialSpy(boolean enabled) {
        return new ChatUser(ignored, replyTo, replyToName, messagesEnabled, enabled, staffChat, chatColor);
    }

    ChatUser withStaffChat(boolean enabled) {
        return new ChatUser(ignored, replyTo, replyToName, messagesEnabled, socialSpy, enabled, chatColor);
    }

    ChatUser withChatColor(@Nullable String color) {
        return new ChatUser(ignored, replyTo, replyToName, messagesEnabled, socialSpy, staffChat, color);
    }
}
