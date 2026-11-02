package dev.yanianz.atrium.module.worth;

import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Dialect;
import dev.yanianz.atrium.core.storage.Migration;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Sale log (also the source of the daily limit) and pending item returns: items that could not be handed
 * back to a player who was offline or dead at that moment. Returns are claimed in one transaction
 * (select + delete) before they are given, so they are delivered at most once.
 */
final class WorthStore {
    static final List<Migration> MIGRATIONS = List.of(new Migration(1, "sales and returns", dialect -> List.of(
            "CREATE TABLE {prefix}worth_sales (id CHAR(36) NOT NULL PRIMARY KEY, uuid CHAR(36) NOT NULL, day CHAR(10) NOT NULL,"
                    + " created BIGINT NOT NULL, mode VARCHAR(16) NOT NULL, items INT NOT NULL, total VARCHAR(64) NOT NULL,"
                    + " detail TEXT NOT NULL)",
            "CREATE INDEX {prefix}worth_sales_day ON {prefix}worth_sales (uuid, day)",
            "CREATE TABLE {prefix}worth_returns (id CHAR(36) NOT NULL PRIMARY KEY, uuid CHAR(36) NOT NULL, items "
                    + (dialect == Dialect.MYSQL ? "MEDIUMBLOB" : "BLOB") + " NOT NULL, created BIGINT NOT NULL)",
            "CREATE INDEX {prefix}worth_returns_uuid ON {prefix}worth_returns (uuid)")));

    private final Database db;

    WorthStore(Database db) {
        this.db = db;
    }

    CompletableFuture<Void> logSale(UUID player, LocalDate day, long created, String mode, int items, BigDecimal total,
            String detail) {
        String sql = "INSERT INTO " + db.table("worth_sales")
                + " (id, uuid, day, created, mode, items, total, detail) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, UUID.randomUUID().toString(), player.toString(), day.toString(), created, mode, items,
                        total.toPlainString(), detail.length() > 4000 ? detail.substring(0, 4000) : detail);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Total earned by selling on a day; summed exactly in Java. */
    CompletableFuture<BigDecimal> soldOn(UUID player, LocalDate day) {
        String sql = "SELECT total FROM " + db.table("worth_sales") + " WHERE uuid = ? AND day = ?";
        return db.query(c -> {
            BigDecimal sum = BigDecimal.ZERO;
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, player.toString(), day.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        sum = sum.add(new BigDecimal(rs.getString(1)));
                    }
                }
            }
            return sum;
        });
    }

    record Sale(long created, String mode, int items, String total, String detail) {}

    CompletableFuture<List<Sale>> recentSales(UUID player, int limit) {
        String sql = "SELECT created, mode, items, total, detail FROM " + db.table("worth_sales")
                + " WHERE uuid = ? ORDER BY created DESC LIMIT " + Math.max(1, limit);
        return db.query(c -> {
            List<Sale> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Sale(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5)));
                    }
                }
            }
            return out;
        });
    }

    CompletableFuture<Void> addReturn(UUID player, byte[] items) {
        String sql = "INSERT INTO " + db.table("worth_returns") + " (id, uuid, items, created) VALUES (?, ?, ?, ?)";
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, player.toString());
                ps.setBytes(3, items);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Removes and returns every pending return of a player in one transaction. */
    CompletableFuture<List<byte[]>> claimReturns(UUID player) {
        String select = "SELECT id, items FROM " + db.table("worth_returns") + " WHERE uuid = ?";
        String delete = "DELETE FROM " + db.table("worth_returns") + " WHERE id = ?";
        return db.transaction(c -> {
            List<String> ids = new ArrayList<>();
            List<byte[]> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(select)) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getString(1));
                        out.add(rs.getBytes(2));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(delete)) {
                for (String id : ids) {
                    ps.setString(1, id);
                    if (ps.executeUpdate() != 1) {
                        throw new java.sql.SQLException("Pending return " + id + " vanished during claim");
                    }
                }
            }
            return out;
        });
    }
}
