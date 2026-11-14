package dev.yanianz.atrium.module.webstore;

import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Dialect;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Storage of webstore deliveries.
 *
 * <p>{@code {prefix}webstore_deliveries} is a contract with the website: it INSERTs rows (status {@code pending})
 * and SELECTs them; Atrium is the only writer of {@code status}, {@code attempts}, {@code last_error} and
 * {@code delivered_at}. Its columns are fixed.
 *
 * <p><b>Claiming (several servers may share the database).</b> A row is delivered by whichever server first moves
 * it from {@code pending} to {@code processing} with one conditional UPDATE
 * ({@code WHERE id = ? AND status = 'pending' AND attempts < max}, which also increments {@code attempts}). Only the
 * server whose UPDATE changed the row runs the commands, so two servers can never deliver the same row at the same
 * time. In the same transaction the claim is recorded in the Atrium-only table {@code {prefix}webstore_claims}
 * (who, when), because the contract table has no column for a claim time. A server that dies while a row is
 * {@code processing} leaves the claim behind; {@link #recoverStale} puts such rows back to {@code pending} (or
 * {@code failed} once the attempts are used up) after a timeout. Delivery is therefore at-least-once: if a server
 * crashes after running the commands but before {@link #markDelivered}, the row is delivered again after the
 * timeout, so package commands should be idempotent (as {@code lp user ... parent add} is).
 */
final class DeliveryStore {
    static final String PENDING = "pending";
    static final String PROCESSING = "processing";
    static final String DELIVERED = "delivered";
    static final String FAILED = "failed";
    static final List<String> STATUSES = List.of(PENDING, PROCESSING, DELIVERED, FAILED);

    static final int MAX_ERROR_LENGTH = 255;

    static final List<Migration> MIGRATIONS = List.of(new Migration(1, "webstore deliveries and claims", dialect -> {
        boolean sqlite = dialect == Dialect.SQLITE;
        String id = sqlite ? "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT" : "id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY";
        String columns = id
                + ", order_id VARCHAR(64) NOT NULL UNIQUE,"
                + " package_id VARCHAR(64) NOT NULL,"
                + " recipient_uuid CHAR(36) NOT NULL,"
                + " recipient_name VARCHAR(16) NOT NULL,"
                + " buyer_name VARCHAR(64) NULL,"
                + " gift TINYINT(1) NOT NULL DEFAULT 0,"
                + " gift_message VARCHAR(140) NULL,"
                + " status VARCHAR(16) NOT NULL DEFAULT 'pending',"
                + " attempts INT NOT NULL DEFAULT 0,"
                + " last_error VARCHAR(255) NULL,"
                + " created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + " delivered_at DATETIME NULL";
        String claims = "CREATE TABLE IF NOT EXISTS {prefix}webstore_claims (delivery_id "
                + (sqlite ? "INTEGER" : "BIGINT UNSIGNED") + " NOT NULL PRIMARY KEY,"
                + " claimed_by VARCHAR(64) NOT NULL, claimed_at BIGINT NOT NULL)";
        return sqlite
                ? List.of("CREATE TABLE IF NOT EXISTS {prefix}webstore_deliveries (" + columns + ")",
                        "CREATE INDEX IF NOT EXISTS {prefix}idx_webstore_status ON {prefix}webstore_deliveries (status, id)",
                        claims)
                : List.of("CREATE TABLE IF NOT EXISTS {prefix}webstore_deliveries (" + columns
                                + ", INDEX idx_webstore_status (status, id))",
                        claims);
    }));

    /** One delivery row as read for processing. */
    record Delivery(long id, String orderId, String packageId, String recipientUuid, String recipientName,
            @Nullable String buyerName, boolean gift, @Nullable String giftMessage, int attempts) {}

    private final Database db;
    private final String deliveries;
    private final String claims;

    DeliveryStore(Database db) {
        this.db = db;
        this.deliveries = db.table("webstore_deliveries");
        this.claims = db.table("webstore_claims");
    }

    /** Up to {@code limit} pending rows that still have attempts left, oldest first. */
    CompletableFuture<List<Delivery>> pending(int limit, int maxAttempts) {
        String sql = "SELECT id, order_id, package_id, recipient_uuid, recipient_name, buyer_name, gift, gift_message,"
                + " attempts FROM " + deliveries + " WHERE status = ? AND attempts < ? ORDER BY id LIMIT ?";
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, PENDING, maxAttempts, limit);
                List<Delivery> list = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new Delivery(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                rs.getString(5), rs.getString(6), rs.getInt(7) != 0, rs.getString(8), rs.getInt(9)));
                    }
                }
                return list;
            }
        });
    }

    /**
     * Atomically claims a pending row for this server (pending → processing, attempts + 1). Completes with
     * {@code true} only for the one caller whose update changed the row.
     */
    CompletableFuture<Boolean> claim(long id, int maxAttempts, String claimer, long nowMillis) {
        return db.transaction(c -> {
            int changed;
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + deliveries
                    + " SET status = ?, attempts = attempts + 1 WHERE id = ? AND status = ? AND attempts < ?")) {
                Database.bind(ps, PROCESSING, id, PENDING, maxAttempts);
                changed = ps.executeUpdate();
            }
            if (changed != 1) {
                return false;
            }
            deleteClaim(c, id);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + claims
                    + " (delivery_id, claimed_by, claimed_at) VALUES (?, ?, ?)")) {
                Database.bind(ps, id, claimer, nowMillis);
                ps.executeUpdate();
            }
            return true;
        });
    }

    /**
     * processing → delivered. A row that a stale-claim sweep already put back to pending (a very slow delivery) is
     * also marked delivered, so it is not delivered a second time. Completes with {@code false} when the row was in
     * neither state (for example retried or failed by someone else meanwhile).
     */
    CompletableFuture<Boolean> markDelivered(long id) {
        return db.transaction(c -> {
            int changed;
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + deliveries
                    + " SET status = ?, delivered_at = CURRENT_TIMESTAMP, last_error = NULL"
                    + " WHERE id = ? AND status IN (?, ?)")) {
                Database.bind(ps, DELIVERED, id, PROCESSING, PENDING);
                changed = ps.executeUpdate();
            }
            deleteClaim(c, id);
            return changed == 1;
        });
    }

    /**
     * A claimed delivery failed: back to pending while attempts are left, otherwise failed. Completes with the new
     * status, or {@code null} when the row was no longer processing.
     */
    CompletableFuture<@Nullable String> failAttempt(long id, int maxAttempts, String error) {
        return db.transaction(c -> {
            int changed;
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + deliveries
                    + " SET status = CASE WHEN attempts >= ? THEN ? ELSE ? END, last_error = ?"
                    + " WHERE id = ? AND status = ?")) {
                Database.bind(ps, maxAttempts, FAILED, PENDING, truncate(error), id, PROCESSING);
                changed = ps.executeUpdate();
            }
            deleteClaim(c, id);
            return changed == 1 ? statusOf(c, id) : null;
        });
    }

    /**
     * pending → failed without running anything (unknown package, unusable values). Only touches a pending row, so
     * it cannot interfere with a server that claimed the row meanwhile.
     */
    CompletableFuture<Boolean> failPending(long id, String error) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + deliveries
                    + " SET status = ?, last_error = ? WHERE id = ? AND status = ?")) {
                Database.bind(ps, FAILED, truncate(error), id, PENDING);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /**
     * Housekeeping, run before each poll: processing rows whose claim is older than {@code claimedBefore} (or has no
     * claim at all) go back to pending, or to failed when no attempt is left; pending rows that have no attempts
     * left (for example after max-attempts was lowered) become failed. Completes with the number of rows changed.
     */
    CompletableFuture<Integer> recoverStale(long claimedBefore, int maxAttempts) {
        return db.transaction(c -> {
            List<Long> stale = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT d.id FROM " + deliveries + " d LEFT JOIN " + claims
                    + " k ON k.delivery_id = d.id WHERE d.status = ? AND (k.claimed_at IS NULL OR k.claimed_at < ?)")) {
                Database.bind(ps, PROCESSING, claimedBefore);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        stale.add(rs.getLong(1));
                    }
                }
            }
            int changed = 0;
            for (long id : stale) {
                // Re-check the claim in the UPDATE itself: another server may have re-claimed the row meanwhile.
                int updated;
                try (PreparedStatement ps = c.prepareStatement("UPDATE " + deliveries
                        + " SET status = CASE WHEN attempts >= ? THEN ? ELSE ? END, last_error = ?"
                        + " WHERE id = ? AND status = ? AND NOT EXISTS (SELECT 1 FROM " + claims
                        + " WHERE delivery_id = ? AND claimed_at >= ?)")) {
                    Database.bind(ps, maxAttempts, FAILED, PENDING,
                            "claim expired: the server delivering it stopped or timed out", id, PROCESSING, id, claimedBefore);
                    updated = ps.executeUpdate();
                }
                if (updated == 1) {
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + claims
                            + " WHERE delivery_id = ? AND claimed_at < ?")) {
                        Database.bind(ps, id, claimedBefore);
                        ps.executeUpdate();
                    }
                }
                changed += updated;
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + deliveries
                    + " SET status = ?, last_error = COALESCE(last_error, ?) WHERE status = ? AND attempts >= ?")) {
                Database.bind(ps, FAILED, "no attempts left", PENDING, maxAttempts);
                changed += ps.executeUpdate();
            }
            return changed;
        });
    }

    /**
     * Outcome of {@link #retry}: {@code retried} when the order was failed and is pending again; otherwise
     * {@code status} is its current status, or {@code null} when the order does not exist.
     */
    record RetryResult(boolean retried, @Nullable String status) {}

    /** failed → pending with attempts reset and last_error cleared. */
    CompletableFuture<RetryResult> retry(String orderId) {
        return db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + deliveries
                    + " SET status = ?, attempts = 0, last_error = NULL WHERE order_id = ? AND status = ?")) {
                Database.bind(ps, PENDING, orderId, FAILED);
                if (ps.executeUpdate() == 1) {
                    return new RetryResult(true, PENDING);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT status FROM " + deliveries + " WHERE order_id = ?")) {
                ps.setString(1, orderId);
                try (ResultSet rs = ps.executeQuery()) {
                    return new RetryResult(false, rs.next() ? rs.getString(1) : null);
                }
            }
        });
    }

    /** Row count per status; every known status is present (0 when none), unknown values are added as found. */
    CompletableFuture<Map<String, Integer>> counts() {
        return db.query(c -> {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String status : STATUSES) {
                counts.put(status, 0);
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT status, COUNT(*) FROM " + deliveries + " GROUP BY status");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getString(1), rs.getInt(2));
                }
            }
            return counts;
        });
    }

    private @Nullable String statusOf(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT status FROM " + deliveries + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void deleteClaim(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + claims + " WHERE delivery_id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    static String truncate(String error) {
        String text = error == null || error.isBlank() ? "unknown error" : error.strip();
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH - 3) + "...";
    }
}
