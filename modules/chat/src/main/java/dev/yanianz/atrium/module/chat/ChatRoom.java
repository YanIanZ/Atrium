package dev.yanianz.atrium.module.chat;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** One persistent chat room and the access rules that need no server state. */
record ChatRoom(String name, UUID owner, Set<UUID> members, Set<UUID> invited, boolean locked, long updatedAt) {
    ChatRoom {
        name = name == null ? "" : name.toLowerCase(Locale.ROOT);
        members = Set.copyOf(members);
        invited = Set.copyOf(invited);
    }

    static Optional<String> normaliseName(String raw, int maxLength) {
        if (raw == null) {
            return Optional.empty();
        }
        String name = raw.trim().toLowerCase(Locale.ROOT);
        int limit = Math.max(1, maxLength);
        if (name.isEmpty() || name.length() > limit) {
            return Optional.empty();
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-')) {
                return Optional.empty();
            }
        }
        return Optional.of(name);
    }

    boolean isOwner(UUID uuid) {
        return owner != null && owner.equals(uuid);
    }

    boolean isMember(UUID uuid) {
        return members.contains(uuid);
    }

    boolean canJoin(UUID uuid, boolean publicJoin) {
        return isOwner(uuid) || isMember(uuid) || invited.contains(uuid) || (!locked && publicJoin);
    }

    ChatRoom withMember(UUID uuid) {
        Set<UUID> next = new LinkedHashSet<>(members);
        next.add(uuid);
        Set<UUID> invites = new LinkedHashSet<>(invited);
        invites.remove(uuid);
        return new ChatRoom(name, owner, next, invites, locked, updatedAt);
    }

    ChatRoom withoutMember(UUID uuid) {
        Set<UUID> next = new LinkedHashSet<>(members);
        next.remove(uuid);
        return new ChatRoom(name, owner, next, invited, locked, updatedAt);
    }

    ChatRoom withInvited(UUID uuid) {
        Set<UUID> next = new LinkedHashSet<>(invited);
        next.add(uuid);
        return new ChatRoom(name, owner, members, next, locked, updatedAt);
    }

    ChatRoom withLocked(boolean value) {
        return new ChatRoom(name, owner, members, invited, value, updatedAt);
    }

    ChatRoom withOwner(UUID uuid) {
        return new ChatRoom(name, uuid, members, invited, locked, updatedAt);
    }

    ChatRoom touched(long now) {
        return new ChatRoom(name, owner, members, invited, locked, now);
    }
}
