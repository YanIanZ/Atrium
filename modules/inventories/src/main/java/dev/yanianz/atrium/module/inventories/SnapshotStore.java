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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Snapshot rows in {@code {prefix}inventories_snapshots}. Item bytes are stored exactly as produced by
 * {@link SnapshotCodec}; the row also carries the format version, so a newer layout is refused instead of being
 * decoded with the wrong assumptions.
 *
 * <p>No row is ever loaded into memory by the store itself; lists read metadata only and a load reads one
 * row by id.
 */
final class SnapshotStore {
    static final List<Migration> MIGRATIONS = List.of(new Migration(1, "inventory snapshots", dialect -> {
        String id = dialect == Dialect.SQLITE
                ? "id INTEGER PRIMARY KEY AUTOINCREMENT"
                : "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY";
        String blob = dialect == Dialect.SQLITE ? "BLOB" : "LONGBLOB";
        return List.of(
                "CREATE TABLE {prefix}inventories_snapshots (" + id
                        + ", target CHAR(36) NOT NULL, player_name VARCHAR(16) NOT NULL, format INT NOT NULL"
                        + ", created BIGINT NOT NULL, creator CHAR(36), creator_name VARCHAR(64) NOT NULL"
                        + ", label VARCHAR(64), reason VARCHAR(24) NOT NULL, stacks INT NOT NULL"
                        + ", items " + blob + " NOT NULL)",
                "CREATE INDEX {prefix}inventories_target ON {prefix}inventories_snapshots (target, id)",
                "CREATE INDEX {prefix}inventories_created ON {prefix}inventories_snapshots (created)");
    }));

    private static final String COLUMNS =
            "id, target, player_name, format, created, creator_name, label, reason, stacks";
    private static final int DELETE_BATCH = 500;

    record Meta(long id, UUID target, String playerName, int format, long created, String creatorName,
            String label, SnapshotReason reason, int stacks) {}

    record Stored(Meta meta, byte[] items) {}

    record Draft(UUID target, String playerName, int format, long created, UUID creator, String creatorName,
            String label, SnapshotReason reason, int stacks, byte[] items) {}

    record Page(int page, int pages, int total, List<Meta> entries) {}

    private final Database db;
    private final String table;

    SnapshotStore(Database db) {
        this.db = db;
        this.table = db.table("inventories_snapshots");
    }

    CompletableFuture<Long> insert(Draft draft) {
        String sql = "INSERT INTO " + table + " (target, player_name, format, created, creator, creator_name, "
                + "label, reason, stacks, items) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        return db.transaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                Database.bind(ps, draft.target().toString(), draft.playerName(), draft.format(), draft.created(),
                        draft.creator() == null ? null : draft.creator().toString(), draft.creatorName(),
                        draft.label(), draft.reason().name(), draft.stacks(), draft.items());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("No id generated for a new inventory snapshot");
                    }
                    return keys.getLong(1);
                }
            }
        });
    }

    /** One page of metadata, newest first. */
    CompletableFuture<Page> listPage(UUID target, int requestedPage, int pageSize) {
        int size = Math.max(1, pageSize);
        return db.query(connection -> {
            int total;
            try (PreparedStatement count = connection.prepareStatement(
                    "SELECT COUNT(*) FROM " + table + " WHERE target = ?")) {
                count.setString(1, target.toString());
                try (ResultSet rs = count.executeQuery()) {
                    total = rs.next() ? rs.getInt(1) : 0;
                }
            }
            int pages = Paging.pages(total, size);
            int page = Paging.clamp(requestedPage, total, size);
            List<Meta> entries = new ArrayList<>();
            if (total > 0) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT " + COLUMNS + " FROM " + table + " WHERE target = ? ORDER BY id DESC LIMIT ? OFFSET ?")) {
                    ps.setString(1, target.toString());
                    ps.setInt(2, size);
                    ps.setInt(3, (page - 1) * size);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            entries.add(meta(rs));
                        }
                    }
                }
            }
            return new Page(page, pages, total, List.copyOf(entries));
        });
    }

    /** The newest snapshot of a player, including its bytes (used for offline /invsave). */
    CompletableFuture<Optional<Stored>> latest(UUID target) {
        return latest(target, null);
    }

    /**
     * The newest snapshot of a player, optionally only one reason (the QUIT snapshot is the offline state).
     * A {@code null} reason means "any reason".
     */
    CompletableFuture<Optional<Stored>> latest(UUID target, SnapshotReason reason) {
        String sql = reason == null
                ? "SELECT " + COLUMNS + ", items FROM " + table + " WHERE target = ? ORDER BY id DESC LIMIT 1"
                : "SELECT " + COLUMNS + ", items FROM " + table + " WHERE target = ? AND reason = ? "
                        + "ORDER BY id DESC LIMIT 1";
        return db.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, target.toString());
                if (reason != null) {
                    ps.setString(2, reason.name());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(stored(rs)) : Optional.empty();
                }
            }
        });
    }

    /** One snapshot, only when it belongs to the target. */
    CompletableFuture<Optional<Stored>> get(UUID target, long id) {
        return db.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + ", items FROM " + table + " WHERE target = ? AND id = ?")) {
                ps.setString(1, target.toString());
                ps.setLong(2, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(stored(rs)) : Optional.empty();
                }
            }
        });
    }

    /** Deletes one snapshot that belongs to the target. */
    CompletableFuture<Boolean> delete(UUID target, long id) {
        return db.update("DELETE FROM " + table + " WHERE target = ? AND id = ?", target.toString(), id)
                .thenApply(deleted -> deleted > 0);
    }

    /** Deletes every snapshot of the target. Returns how many rows were removed. */
    CompletableFuture<Integer> deleteAll(UUID target) {
        return db.update("DELETE FROM " + table + " WHERE target = ?", target.toString());
    }

    /**
     * Applies the retention rules to one player: count and age. Failures are the caller's to log; an
     * unsuccessful prune only leaves extra rows, never loses a snapshot that should have been kept.
     */
    CompletableFuture<Integer> prune(UUID target, int maxCount, long cutoff) {
        return db.transaction(connection -> {
            List<Retention.Entry> entries = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, created FROM " + table + " WHERE target = ?")) {
                ps.setString(1, target.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        entries.add(new Retention.Entry(rs.getLong(1), rs.getLong(2)));
                    }
                }
            }
            List<Long> remove = Retention.toDelete(entries, maxCount, cutoff);
            return deleteIds(connection, target, remove);
        });
    }

    /** Global age purge; runs on enable and periodically. */
    CompletableFuture<Integer> purgeOlderThan(long cutoff) {
        if (cutoff <= 0) {
            return CompletableFuture.completedFuture(0);
        }
        return db.update("DELETE FROM " + table + " WHERE created < ?", cutoff);
    }

    private int deleteIds(Connection connection, UUID target, List<Long> ids) throws SQLException {
        int removed = 0;
        for (int start = 0; start < ids.size(); start += DELETE_BATCH) {
            List<Long> batch = ids.subList(start, Math.min(start + DELETE_BATCH, ids.size()));
            String placeholders = String.join(", ", java.util.Collections.nCopies(batch.size(), "?"));
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE target = ? AND id IN (" + placeholders + ")")) {
                ps.setString(1, target.toString());
                for (int index = 0; index < batch.size(); index++) {
                    ps.setLong(index + 2, batch.get(index));
                }
                removed += ps.executeUpdate();
            }
        }
        return removed;
    }

    private static Meta meta(ResultSet rs) throws SQLException {
        return new Meta(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getInt(4),
                rs.getLong(5), rs.getString(6), rs.getString(7), SnapshotReason.parse(rs.getString(8)), rs.getInt(9));
    }

    private static Stored stored(ResultSet rs) throws SQLException {
        return new Stored(meta(rs), rs.getBytes(10));
    }
}
