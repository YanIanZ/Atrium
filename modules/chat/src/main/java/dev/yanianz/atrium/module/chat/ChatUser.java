package dev.yanianz.atrium.module.chat;

import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Immutable per-player chat state. A missing reply target is {@code null}; the staff-chat toggle, default
 * chat colour, active room, selected tag and range-spy toggle are persisted so a restart does not silently
 * drop them.
 */
record ChatUser(Set<UUID> ignored, @Nullable UUID replyTo, @Nullable String replyToName, boolean messagesEnabled,
        boolean socialSpy, boolean staffChat, @Nullable String chatColor, @Nullable String chatRoom,
        @Nullable String chatTag, boolean rangeSpy) {
    static final ChatUser DEFAULT = new ChatUser(Set.of(), null, null, true, false, false, null, null, null, false);

    ChatUser {
        ignored = Set.copyOf(ignored);
        replyToName = replyToName == null || replyToName.isBlank() ? null : replyToName;
        chatColor = chatColor == null || chatColor.isBlank() ? null : chatColor;
        chatRoom = chatRoom == null || chatRoom.isBlank() ? null : chatRoom;
        chatTag = chatTag == null || chatTag.isBlank() ? null : chatTag;
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
        return new ChatUser(next, replyTo, replyToName, messagesEnabled, socialSpy, staffChat, chatColor, chatRoom,
                chatTag, rangeSpy);
    }

    ChatUser withReplyTo(@Nullable UUID uuid, @Nullable String name) {
        return new ChatUser(ignored, uuid, name, messagesEnabled, socialSpy, staffChat, chatColor, chatRoom, chatTag,
                rangeSpy);
    }

    ChatUser withMessagesEnabled(boolean enabled) {
        return new ChatUser(ignored, replyTo, replyToName, enabled, socialSpy, staffChat, chatColor, chatRoom, chatTag,
                rangeSpy);
    }

    ChatUser withSocialSpy(boolean enabled) {
        return new ChatUser(ignored, replyTo, replyToName, messagesEnabled, enabled, staffChat, chatColor, chatRoom,
                chatTag, rangeSpy);
    }

    ChatUser withStaffChat(boolean enabled) {
        return new ChatUser(ignored, replyTo, replyToName, messagesEnabled, socialSpy, enabled, chatColor, chatRoom,
                chatTag, rangeSpy);
    }

    ChatUser withChatColor(@Nullable String color) {
        return new ChatUser(ignored, replyTo, replyToName, messagesEnabled, socialSpy, staffChat, color, chatRoom,
                chatTag, rangeSpy);
    }

    ChatUser withChatRoom(@Nullable String room) {
        return new ChatUser(ignored, replyTo, replyToName, messagesEnabled, socialSpy, staffChat, chatColor, room,
                chatTag, rangeSpy);
    }

    ChatUser withChatTag(@Nullable String tag) {
        return new ChatUser(ignored, replyTo, replyToName, messagesEnabled, socialSpy, staffChat, chatColor, chatRoom,
                tag, rangeSpy);
    }

    ChatUser withRangeSpy(boolean enabled) {
        return new ChatUser(ignored, replyTo, replyToName, messagesEnabled, socialSpy, staffChat, chatColor, chatRoom,
                chatTag, enabled);
    }
}
