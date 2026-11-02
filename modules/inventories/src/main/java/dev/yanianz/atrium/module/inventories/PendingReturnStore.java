package dev.yanianz.atrium.module.inventories;

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
 * Items owed to an administrator whose offline edit never reached its target, in
 * {@code {prefix}inventories_returns}. Rows are created when the editor is offline and deleted as soon as the
 * items were given on their own thread; an undelivered row is never purged, so items are not lost while an
 * administrator stays away.
 *
 * <p>The same migration also adds the contributed-stack columns to {@code {prefix}inventories_pending}, next to
 * the existing editor id. A row stored before this migration has no delta and therefore refunds nothing.
 */
final class PendingReturnStore {
    static final List<Migration> MIGRATIONS = List.of(new Migration(3, "return stacks of rejected offline edits",
            dialect -> {
                String id = dialect == Dialect.SQLITE
                        ? "id INTEGER PRIMARY KEY AUTOINCREMENT"
                        : "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY";
                String blob = dialect == Dialect.SQLITE ? "BLOB" : "LONGBLOB";
                return List.of(
                        "ALTER TABLE {prefix}inventories_pending ADD COLUMN delta_stacks INT NOT NULL DEFAULT 0",
                        "ALTER TABLE {prefix}inventories_pending ADD COLUMN delta_items " + blob,
                        "CREATE TABLE {prefix}inventories_returns (" + id
                                + ", admin CHAR(36) NOT NULL, admin_name VARCHAR(64) NOT NULL"
                                + ", created BIGINT NOT NULL, reason VARCHAR(32) NOT NULL"
                                + ", detail VARCHAR(64), stacks INT NOT NULL, items " + blob + " NOT NULL)",
                        "CREATE INDEX {prefix}inventories_returns_admin"
                                + " ON {prefix}inventories_returns (admin, id)");
            }));

    /** One owed payload: the stacks the editor added to an edit that was never applied. */
    record Row(long id, UUID admin, String adminName, long created, String reason, String detail, int stacks,
            byte[] items) {}

    record Draft(UUID admin, String adminName, long created, String reason, String detail, int stacks,
            byte[] items) {}

    private static final String COLUMNS = "id, admin, admin_name, created, reason, detail, stacks";

    private final Database db;
    private final String table;

    PendingReturnStore(Database db) {
        this.db = db;
        this.table = db.table("inventories_returns");
    }

    CompletableFuture<Long> insert(Draft draft) {
        String sql = "INSERT INTO " + table + " (admin, admin_name, created, reason, detail, stacks, items)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)";
        return db.transaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                Database.bind(ps, draft.admin().toString(), draft.adminName(), draft.created(), draft.reason(),
                        draft.detail(), draft.stacks(), draft.items());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("No id generated for a pending item return");
                    }
                    return keys.getLong(1);
                }
            }
        });
    }

    /** Every payload still owed to one administrator, oldest first. */
    CompletableFuture<List<Row>> pending(UUID admin) {
        return db.query(connection -> {
            List<Row> rows = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + ", items FROM " + table + " WHERE admin = ? ORDER BY id")) {
                ps.setString(1, admin.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(row(rs));
                    }
                }
            }
            return List.copyOf(rows);
        });
    }

    /** Deletes one payload after its items were given. Returns false when it was already gone. */
    CompletableFuture<Boolean> delete(long id) {
        return db.update("DELETE FROM " + table + " WHERE id = ?", id).thenApply(deleted -> deleted > 0);
    }

    static String reason(String raw) {
        String cleaned = raw == null || raw.isBlank() ? "rejected-edit" : raw.strip();
        return cleaned.length() <= 32 ? cleaned : cleaned.substring(0, 32);
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getLong(4),
                rs.getString(5), rs.getString(6), rs.getInt(7), rs.getBytes(8));
    }
}
