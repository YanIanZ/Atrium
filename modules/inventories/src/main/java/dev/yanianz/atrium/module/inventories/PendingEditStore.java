package dev.yanianz.atrium.module.inventories;

import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Dialect;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Pending offline edits in {@code {prefix}inventories_pending}. A row stores the edited copy plus the id of the
 * snapshot it was derived from; rows are never applied by this class, only recorded, resolved and pruned.
 *
 * <p>Scheduling a new edit closes the previous open edit of the same player in the same transaction, so at
 * most one edit can ever wait for a join. Resolving is conditional on the row still being open, which makes
 * an accidental double resolution harmless. Each row also stores the stacks the editor contributed (added over
 * the base copy), so a rejected or superseded edit can return exactly those items; the editor id is stored
 * next to them.
 */
final class PendingEditStore {
    static final List<Migration> MIGRATIONS = List.of(new Migration(2, "pending offline inventory edits", dialect -> {
        String id = dialect == Dialect.SQLITE
                ? "id INTEGER PRIMARY KEY AUTOINCREMENT"
                : "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY";
        String blob = dialect == Dialect.SQLITE ? "BLOB" : "LONGBLOB";
        return List.of(
                "CREATE TABLE {prefix}inventories_pending (" + id
                        + ", target CHAR(36) NOT NULL, player_name VARCHAR(16) NOT NULL"
                        + ", base_snapshot BIGINT NOT NULL, format INT NOT NULL, created BIGINT NOT NULL"
                        + ", editor CHAR(36), editor_name VARCHAR(64) NOT NULL"
                        + ", status VARCHAR(16) NOT NULL, detail VARCHAR(32)"
                        + ", stacks INT NOT NULL, items " + blob + " NOT NULL)",
                "CREATE INDEX {prefix}inventories_pending_target ON {prefix}inventories_pending (target, status)",
                "CREATE INDEX {prefix}inventories_pending_created ON {prefix}inventories_pending (created)");
    }));

    enum Status {
        PENDING,
        APPLIED,
        DISCARDED,
        SUPERSEDED;

        static Status parse(String raw) {
            try {
                return valueOf(raw == null ? "PENDING" : raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return PENDING;
            }
        }
    }

    record Row(long id, UUID target, String playerName, long baseSnapshot, int format, long created,
            UUID editor, String editorName, Status status, String detail, int stacks, byte[] items,
            int deltaStacks, byte[] deltaItems) {}

    record Draft(UUID target, String playerName, long baseSnapshot, int format, long created, UUID editor,
            String editorName, int stacks, byte[] items, int deltaStacks, byte[] deltaItems) {}

    /** An open edit that a newer edit replaced: its editor still has to get the contributed stacks back. */
    record Superseded(long id, String playerName, UUID editor, String editorName, int deltaStacks,
            byte[] deltaItems) {}

    /** The new row id plus the edits it closed in the same transaction. */
    record Inserted(long id, List<Superseded> superseded) {}

    private static final String COLUMNS = "id, target, player_name, base_snapshot, format, created, editor, "
            + "editor_name, status, detail, stacks, delta_stacks";

    private final Database db;
    private final String table;

    PendingEditStore(Database db) {
        this.db = db;
        this.table = db.table("inventories_pending");
    }

    /** Closes the previous open edits of the target and stores the new one, atomically. */
    CompletableFuture<Inserted> insert(Draft draft) {
        String open = "SELECT id, player_name, editor, editor_name, delta_stacks, delta_items FROM " + table
                + " WHERE target = ? AND status = ? ORDER BY id";
        String close = "UPDATE " + table + " SET status = ?, detail = ? WHERE target = ? AND status = ?";
        String sql = "INSERT INTO " + table + " (target, player_name, base_snapshot, format, created, editor, "
                + "editor_name, status, detail, stacks, items, delta_stacks, delta_items)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        return db.transaction(connection -> {
            List<Superseded> superseded = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(open)) {
                select.setString(1, draft.target().toString());
                select.setString(2, Status.PENDING.name());
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        String editor = rs.getString(3);
                        superseded.add(new Superseded(rs.getLong(1), rs.getString(2),
                                editor == null ? null : UUID.fromString(editor), rs.getString(4), rs.getInt(5),
                                rs.getBytes(6)));
                    }
                }
            }
            try (PreparedStatement update = connection.prepareStatement(close)) {
                update.setString(1, Status.SUPERSEDED.name());
                update.setString(2, "newer-edit");
                update.setString(3, draft.target().toString());
                update.setString(4, Status.PENDING.name());
                update.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                Database.bind(ps, draft.target().toString(), draft.playerName(), draft.baseSnapshot(), draft.format(),
                        draft.created(), draft.editor() == null ? null : draft.editor().toString(),
                        draft.editorName(), Status.PENDING.name(), null, draft.stacks(), draft.items(),
                        draft.deltaStacks(), draft.deltaItems());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("No id generated for a pending inventory edit");
                    }
                    return new Inserted(keys.getLong(1), List.copyOf(superseded));
                }
            }
        });
    }

    /** Every edit still waiting for a join, oldest first (startup preload). */
    CompletableFuture<List<Row>> unresolved() {
        return db.query(connection -> {
            List<Row> rows = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + ", items, delta_items FROM " + table
                            + " WHERE status = ? ORDER BY id")) {
                ps.setString(1, Status.PENDING.name());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(row(rs));
                    }
                }
            }
            return List.copyOf(rows);
        });
    }

    /** The newest open edit of one player, if any. */
    CompletableFuture<Optional<Row>> latestUnresolved(UUID target) {
        return db.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + ", items, delta_items FROM " + table
                            + " WHERE target = ? AND status = ? ORDER BY id DESC LIMIT 1")) {
                ps.setString(1, target.toString());
                ps.setString(2, Status.PENDING.name());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(row(rs)) : Optional.empty();
                }
            }
        });
    }

    /** Resolves one open row. Returns false when it was already resolved (nothing changed). */
    CompletableFuture<Boolean> resolve(long id, Status status, String detail) {
        return db.update("UPDATE " + table + " SET status = ?, detail = ? WHERE id = ? AND status = ?",
                status.name(), detail, id, Status.PENDING.name()).thenApply(changed -> changed > 0);
    }

    /** Resolves every open row of a player (used when their base disappears). */
    CompletableFuture<Integer> resolveOpen(UUID target, Status status, String detail) {
        return db.update("UPDATE " + table + " SET status = ?, detail = ? WHERE target = ? AND status = ?",
                status.name(), detail, target.toString(), Status.PENDING.name());
    }

    /**
     * Age purge for resolved rows only. An open edit must stay until its target joins (or an administrator
     * clears it), otherwise the cached copy and the stored row would disagree.
     */
    CompletableFuture<Integer> purgeOlderThan(long cutoff) {
        if (cutoff <= 0) {
            return CompletableFuture.completedFuture(0);
        }
        return db.update("DELETE FROM " + table + " WHERE created < ? AND status <> ?", cutoff,
                Status.PENDING.name());
    }

    private static Row row(ResultSet rs) throws SQLException {
        String editor = rs.getString(7);
        return new Row(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getLong(4),
                rs.getInt(5), rs.getLong(6), editor == null ? null : UUID.fromString(editor), rs.getString(8),
                Status.parse(rs.getString(9)), rs.getString(10), rs.getInt(11), rs.getBytes(13), rs.getInt(12),
                rs.getBytes(14));
    }
}
