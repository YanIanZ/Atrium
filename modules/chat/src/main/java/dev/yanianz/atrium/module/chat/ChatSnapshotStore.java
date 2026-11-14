package dev.yanianz.atrium.module.chat;

import dev.yanianz.atrium.core.storage.Database;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Short-lived inventory snapshots behind the {@code [inv]} and {@code [ender]} chat tokens.
 *
 * <p>Rows live in {@code {prefix}chat_snapshots}, created by migration v6 of {@link ChatStore}, so every
 * server of the network can open a link with {@code /chatview <id>}. The payload is what
 * {@link org.bukkit.inventory.ItemStack#serializeItemsAsBytes(org.bukkit.inventory.ItemStack[])} produced on
 * the owner's own thread; this class only moves bytes and never decodes them.
 */
final class ChatSnapshotStore {
    static final String KIND_INVENTORY = "inv";
    static final String KIND_ENDER = "ender";
    static final int ID_CHARS = 12;

    record Snapshot(String id, UUID owner, String ownerName, String kind, long createdAt, byte[] payload) {}

    private final Database db;
    private final String table;

    ChatSnapshotStore(Database db) {
        this.db = db;
        this.table = db.table("chat_snapshots");
    }

    CompletableFuture<Void> insert(String id, UUID owner, String ownerName, String kind, long createdAt,
            byte[] payload) {
        return db.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO " + table + " (id, owner, owner_name, kind, created_at, payload) "
                            + "VALUES (?, ?, ?, ?, ?, ?)")) {
                Database.bind(ps, id, owner.toString(), ownerName, kind, createdAt, payload);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Loads one snapshot unless it is older than {@code cutoff}. */
    CompletableFuture<Optional<Snapshot>> load(String id, long cutoff) {
        return db.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, owner, owner_name, kind, created_at, payload FROM " + table
                            + " WHERE id = ? AND created_at >= ?")) {
                ps.setString(1, id);
                ps.setLong(2, cutoff);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    UUID owner;
                    try {
                        owner = UUID.fromString(rs.getString(2));
                    } catch (IllegalArgumentException e) {
                        return Optional.empty();
                    }
                    return Optional.of(new Snapshot(rs.getString(1), owner, rs.getString(3), rs.getString(4),
                            rs.getLong(5), rs.getBytes(6)));
                }
            }
        });
    }

    CompletableFuture<Integer> purgeOlderThan(long cutoff) {
        return db.update("DELETE FROM " + table + " WHERE created_at < ?", cutoff);
    }
}
