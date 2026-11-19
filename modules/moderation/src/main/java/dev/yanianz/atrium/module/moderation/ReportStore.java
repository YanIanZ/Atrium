package dev.yanianz.atrium.module.moderation;

import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Dialect;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Player reports ({@code {prefix}moderation_reports}). With a shared MySQL database every server reads and
 * writes the same queue, so a report made on one server is handled from any other.
 */
final class ReportStore {
    static final String OPEN = "OPEN";
    static final String CLAIMED = "CLAIMED";
    static final String RESOLVED = "RESOLVED";
    static final String REJECTED = "REJECTED";

    static final Migration MIGRATION = new Migration(6, "player reports", dialect -> List.of(
            "CREATE TABLE {prefix}moderation_reports ("
                    + (dialect == Dialect.SQLITE
                            ? "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, "
                            : "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, ")
                    + "target CHAR(36) NOT NULL, target_name VARCHAR(32) NOT NULL, reporter CHAR(36) NOT NULL, "
                    + "reporter_name VARCHAR(32) NOT NULL, reason VARCHAR(256) NOT NULL, server VARCHAR(64) NOT NULL, "
                    + "created BIGINT NOT NULL, status VARCHAR(12) NOT NULL, handler CHAR(36), "
                    + "handler_name VARCHAR(64), handled_at BIGINT)",
            "CREATE INDEX {prefix}moderation_reports_status ON {prefix}moderation_reports (status, created)"));

    /** One report. {@code handler} is the staff member who claimed or closed it. */
    record Report(long id, UUID target, String targetName, UUID reporter, String reporterName, String reason,
            String server, Instant created, String status, @Nullable UUID handler, @Nullable String handlerName,
            @Nullable Instant handledAt) {
        boolean open() {
            return OPEN.equals(status) || CLAIMED.equals(status);
        }
    }

    private static final String COLUMNS = "id, target, target_name, reporter, reporter_name, reason, server, created, "
            + "status, handler, handler_name, handled_at";

    private final Database db;
    private final String table;

    ReportStore(Database db) {
        this.db = db;
        this.table = db.table("moderation_reports");
    }

    CompletableFuture<Long> add(UUID target, String targetName, UUID reporter, String reporterName, String reason,
            String server, Instant created) {
        return db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table + " (target, target_name, reporter, "
                    + "reporter_name, reason, server, created, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS)) {
                Database.bind(ps, target.toString(), targetName, reporter.toString(), reporterName, reason, server,
                        created.toEpochMilli(), OPEN);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new java.sql.SQLException("No id generated for the new report");
                    }
                    return keys.getLong(1);
                }
            }
        });
    }

    /** Reports that are not closed, oldest first. */
    CompletableFuture<List<Report>> open(int limit) {
        return db.query(c -> {
            List<Report> reports = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM " + table
                    + " WHERE status IN (?, ?) ORDER BY id LIMIT ?")) {
                Database.bind(ps, OPEN, CLAIMED, Math.max(1, limit));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        reports.add(read(rs));
                    }
                }
            }
            return List.copyOf(reports);
        });
    }

    CompletableFuture<Optional<Report>> byId(long id) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM " + table + " WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.<Report>empty();
                }
            }
        });
    }

    /** Reports of one player that are not closed yet. */
    CompletableFuture<Integer> openBy(UUID reporter) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table
                    + " WHERE reporter = ? AND status IN (?, ?)")) {
                Database.bind(ps, reporter.toString(), OPEN, CLAIMED);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    /** True when the same reporter already has an open report about the same player. */
    CompletableFuture<Boolean> duplicate(UUID reporter, UUID target) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table
                    + " WHERE reporter = ? AND target = ? AND status IN (?, ?)")) {
                Database.bind(ps, reporter.toString(), target.toString(), OPEN, CLAIMED);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getInt(1) > 0;
                }
            }
        });
    }

    /** Moves a report that is still open to {@code status}. Completes with false when somebody closed it first. */
    CompletableFuture<Boolean> status(long id, String status, UUID handler, String handlerName, Instant when) {
        return db.update("UPDATE " + table + " SET status = ?, handler = ?, handler_name = ?, handled_at = ? "
                + "WHERE id = ? AND status IN (?, ?)", status, handler.toString(), handlerName, when.toEpochMilli(),
                id, OPEN, CLAIMED).thenApply(rows -> rows > 0);
    }

    private static Report read(ResultSet rs) throws java.sql.SQLException {
        String handler = rs.getString(10);
        long handled = rs.getLong(12);
        boolean noHandled = rs.wasNull();
        return new Report(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3),
                UUID.fromString(rs.getString(4)), rs.getString(5), rs.getString(6), rs.getString(7),
                Instant.ofEpochMilli(rs.getLong(8)), rs.getString(9), handler == null ? null : UUID.fromString(handler),
                rs.getString(11), noHandled ? null : Instant.ofEpochMilli(handled));
    }
}
