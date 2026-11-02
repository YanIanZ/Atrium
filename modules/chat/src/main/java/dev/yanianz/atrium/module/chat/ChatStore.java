package dev.yanianz.atrium.module.chat;

import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Persists ignored players, the last reply target, the three chat toggles and the default chat colour in
 * the chat_user table.
 *
 * <p>v2 adds the persisted staff-chat toggle, v3 the chat colour and the name of an offline/remote reply
 * target. Migrations only ever run once per database, so old rows keep the column defaults and no
 * released migration is edited.
 */
final class ChatStore {
    static final List<Migration> MIGRATIONS = List.of(Migration.of(1, "chat_user",
                    "CREATE TABLE {prefix}chat_user (uuid CHAR(36) NOT NULL PRIMARY KEY, ignored TEXT NOT NULL, "
                            + "reply_to CHAR(36), messages_enabled BOOLEAN NOT NULL DEFAULT TRUE, "
                            + "social_spy BOOLEAN NOT NULL DEFAULT FALSE)"),
            Migration.of(2, "chat staff mode",
                    "ALTER TABLE {prefix}chat_user ADD COLUMN staff_chat BOOLEAN NOT NULL DEFAULT FALSE"),
            Migration.of(3, "chat colour and reply name",
                    "ALTER TABLE {prefix}chat_user ADD COLUMN chat_color VARCHAR(32)",
                    "ALTER TABLE {prefix}chat_user ADD COLUMN reply_to_name VARCHAR(16)"));

    private final Database db;
    final UserComponentType<ChatUser> component;

    ChatStore(Database db) {
        this.db = db;
        this.component = new UserComponentType<>("chat:user", this::load, this::save, () -> ChatUser.DEFAULT);
    }

    private CompletableFuture<ChatUser> load(UUID uuid) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ignored, reply_to, reply_to_name, messages_enabled, social_spy, staff_chat, chat_color FROM "
                            + db.table("chat_user") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    return new ChatUser(parseIgnored(rs.getString(1)), parseUuid(rs.getString(2)),
                            rs.getString(3), rs.getBoolean(4), rs.getBoolean(5), rs.getBoolean(6),
                            rs.getString(7));
                }
            }
        });
    }

    private CompletableFuture<Void> save(UUID uuid, ChatUser user) {
        String sql = db.dialect().upsert(db.table("chat_user"),
                "uuid, ignored, reply_to, reply_to_name, messages_enabled, social_spy, staff_chat, chat_color",
                "?, ?, ?, ?, ?, ?, ?, ?", "uuid",
                "ignored, reply_to, reply_to_name, messages_enabled, social_spy, staff_chat, chat_color");
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, uuid.toString(), joinIgnored(user.ignored()),
                        user.replyTo() == null ? null : user.replyTo().toString(),
                        user.replyToName(), user.messagesEnabled(), user.socialSpy(), user.staffChat(),
                        user.chatColor());
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static Set<UUID> parseIgnored(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<UUID> result = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            UUID uuid = parseUuid(part.trim());
            if (uuid != null) {
                result.add(uuid);
            }
        }
        return result;
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String joinIgnored(Set<UUID> ignored) {
        return ignored.stream().map(UUID::toString).sorted().reduce((a, b) -> a + "," + b).orElse("");
    }
}
