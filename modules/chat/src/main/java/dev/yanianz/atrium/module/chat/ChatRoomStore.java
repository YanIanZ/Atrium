package dev.yanianz.atrium.module.chat;

import dev.yanianz.atrium.core.storage.Database;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory room registry backed by the {@code chat_room} table. */
final class ChatRoomStore {
    private final Database db;
    private final Map<String, ChatRoom> rooms = new ConcurrentHashMap<>();

    ChatRoomStore(Database db) {
        this.db = db;
    }

    CompletableFuture<List<ChatRoom>> loadAll() {
        return db.query(c -> {
            List<ChatRoom> loaded = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT name_lower, owner, members, invited, locked, updated_at FROM " + db.table("chat_room"));
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID owner = parseUuid(rs.getString(2));
                    if (owner == null) {
                        continue;
                    }
                    loaded.add(new ChatRoom(rs.getString(1), owner, parseUuids(rs.getString(3)),
                            parseUuids(rs.getString(4)), rs.getBoolean(5), rs.getLong(6)));
                }
            }
            return loaded;
        });
    }

    void install(List<ChatRoom> loaded) {
        rooms.clear();
        for (ChatRoom room : loaded) {
            rooms.put(room.name(), room);
        }
    }

    Optional<ChatRoom> get(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(rooms.get(name.toLowerCase(Locale.ROOT)));
    }

    List<ChatRoom> all() {
        List<ChatRoom> list = new ArrayList<>(rooms.values());
        list.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return list;
    }

    int ownedBy(UUID owner) {
        int count = 0;
        for (ChatRoom room : rooms.values()) {
            if (room.isOwner(owner)) {
                count++;
            }
        }
        return count;
    }

    void put(ChatRoom room) {
        rooms.put(room.name(), room);
    }

    void remove(String name) {
        if (name != null) {
            rooms.remove(name.toLowerCase(Locale.ROOT));
        }
    }

    CompletableFuture<Void> save(ChatRoom room) {
        String sql = db.dialect().upsert(db.table("chat_room"),
                "name_lower, owner, members, invited, locked, updated_at", "?, ?, ?, ?, ?, ?", "name_lower",
                "owner, members, invited, locked, updated_at");
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, room.name(), room.owner().toString(), join(room.members()),
                        join(room.invited()), room.locked(), room.updatedAt());
                ps.executeUpdate();
            }
            return null;
        });
    }

    CompletableFuture<Boolean> delete(String name) {
        return db.update("DELETE FROM {prefix}chat_room WHERE name_lower = ?",
                name == null ? "" : name.toLowerCase(Locale.ROOT)).thenApply(rows -> rows > 0);
    }

    private static String join(Set<UUID> ids) {
        return ids.stream().map(UUID::toString).sorted().reduce((a, b) -> a + "," + b).orElse("");
    }

    private static Set<UUID> parseUuids(String raw) {
        Set<UUID> ids = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return ids;
        }
        for (String part : raw.split(",")) {
            UUID uuid = parseUuid(part);
            if (uuid != null) {
                ids.add(uuid);
            }
        }
        return ids;
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
}
