package dev.yanianz.atrium.module.messaging;

import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Dialect;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Staff alerts in {@code {prefix}messaging_alerts}. A row is a note about one player, shown to online staff
 * when that player joins. Expired rows are filtered in SQL and purged periodically.
 */
final class AlertStore {
    static final List<Migration> MIGRATIONS = List.of(new Migration(1, "messaging_alerts", dialect -> {
        String id = dialect == Dialect.SQLITE
                ? "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT"
                : "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY";
        return List.of(
                "CREATE TABLE {prefix}messaging_alerts (" + id
                        + ", player CHAR(36) NOT NULL, player_name VARCHAR(64) NOT NULL,"
                        + " reason TEXT NOT NULL, created BIGINT NOT NULL, created_by VARCHAR(64) NOT NULL,"
                        + " expires BIGINT NOT NULL DEFAULT 0)",
                "CREATE INDEX {prefix}messaging_alerts_player ON {prefix}messaging_alerts (player, id)",
                "CREATE INDEX {prefix}messaging_alerts_expires ON {prefix}messaging_alerts (expires)");
    }));

    /** One stored alert. {@code expires == 0} means it never expires. */
    record Alert(long id, UUID player, String playerName, String reason, long created, String createdBy,
            long expires) {}

    /** One page of active alerts, newest first. */
    record Page(int page, int pages, int total, List<Alert> entries) {}

    private final Database db;
    private final String table;

    AlertStore(Database db) {
        this.db = db;
        this.table = db.table("messaging_alerts");
    }

    /** Inserts a draft (its {@code id} is ignored) and returns the generated id. */
    CompletableFuture<Long> add(Alert draft) {
        long now = System.currentTimeMillis();
        return db.transaction(connection -> {
            try (PreparedStatement purge = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE expires > 0 AND expires <= ?")) {
                purge.setLong(1, now);
                purge.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO " + table
                            + " (player, player_name, reason, created, created_by, expires)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                Database.bind(ps, draft.player().toString(), draft.playerName(), draft.reason(), draft.created(),
                        draft.createdBy(), draft.expires());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        return keys.getLong(1);
                    }
                }
            }
            // Drivers without generated-key support: the newest row for this player is the one just inserted.
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT MAX(id) FROM " + table + " WHERE player = ?")) {
                ps.setString(1, draft.player().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    CompletableFuture<Integer> removeAll(UUID player) {
        return db.update("DELETE FROM " + table + " WHERE player = ?", player.toString());
    }

    /** Active alerts of one player, oldest first, for the join notification. */
    CompletableFuture<List<Alert>> forPlayer(UUID player, long now) {
        return db.query(connection -> {
            List<Alert> alerts = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, player, player_name, reason, created, created_by, expires FROM " + table
                            + " WHERE player = ? AND (expires = 0 OR expires > ?) ORDER BY id ASC")) {
                ps.setString(1, player.toString());
                ps.setLong(2, now);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        alerts.add(read(rs));
                    }
                }
            }
            return List.copyOf(alerts);
        });
    }

    CompletableFuture<Page> page(int requestedPage, int pageSize, long now) {
        int size = Math.max(1, pageSize);
        return db.query(connection -> {
            int total;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM " + table + " WHERE expires = 0 OR expires > ?")) {
                ps.setLong(1, now);
                try (ResultSet rs = ps.executeQuery()) {
                    total = rs.next() ? rs.getInt(1) : 0;
                }
            }
            int pages = total == 0 ? 0 : (total + size - 1) / size;
            int page = pages == 0 ? 1 : Math.max(1, Math.min(pages, requestedPage));
            List<Alert> entries = new ArrayList<>();
            if (total > 0) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT id, player, player_name, reason, created, created_by, expires FROM " + table
                                + " WHERE expires = 0 OR expires > ? ORDER BY id DESC LIMIT ? OFFSET ?")) {
                    ps.setLong(1, now);
                    ps.setInt(2, size);
                    ps.setInt(3, (page - 1) * size);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            entries.add(read(rs));
                        }
                    }
                }
            }
            return new Page(page, pages, total, List.copyOf(entries));
        });
    }

    CompletableFuture<Integer> purgeExpired(long now) {
        return db.update("DELETE FROM " + table + " WHERE expires > 0 AND expires <= ?", now);
    }

    private static Alert read(ResultSet rs) throws SQLException {
        return new Alert(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getString(4),
                rs.getLong(5), rs.getString(6), rs.getLong(7));
    }
}
