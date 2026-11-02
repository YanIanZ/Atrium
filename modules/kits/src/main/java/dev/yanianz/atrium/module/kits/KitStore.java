package dev.yanianz.atrium.module.kits;

import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Dialect;
import dev.yanianz.atrium.core.storage.Migration;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Kit definitions and per-player usage (cooldowns / one-time claims), both persisted in the database. */
final class KitStore {
    static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "kits and usage", dialect -> List.of(
                    "CREATE TABLE {prefix}kits_kits (name VARCHAR(32) NOT NULL PRIMARY KEY, cooldown BIGINT NOT NULL,"
                            + " one_time BOOLEAN NOT NULL, first_join BOOLEAN NOT NULL, cost VARCHAR(64) NOT NULL, items "
                            + (dialect == Dialect.MYSQL ? "MEDIUMBLOB" : "BLOB") + " NOT NULL, updated BIGINT NOT NULL)",
                    "CREATE TABLE {prefix}kits_usage (uuid CHAR(36) NOT NULL, kit VARCHAR(32) NOT NULL, last_used BIGINT NOT NULL,"
                            + " uses INT NOT NULL, PRIMARY KEY (uuid, kit))")),
            new Migration(2, "kit icon, permission and max uses", dialect -> List.of(
                    "ALTER TABLE {prefix}kits_kits ADD COLUMN icon " + (dialect == Dialect.MYSQL ? "MEDIUMBLOB" : "BLOB"),
                    "ALTER TABLE {prefix}kits_kits ADD COLUMN permission VARCHAR(100) NOT NULL DEFAULT ''",
                    "ALTER TABLE {prefix}kits_kits ADD COLUMN max_uses INT NOT NULL DEFAULT -1")),
            new Migration(3, "kit row version and persisted editor returns", dialect -> List.of(
                    "ALTER TABLE {prefix}kits_kits ADD COLUMN version BIGINT NOT NULL DEFAULT 0",
                    "CREATE TABLE {prefix}kits_returns (" + (dialect == Dialect.MYSQL
                            ? "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY"
                            : "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT")
                            + ", uuid CHAR(36) NOT NULL, items " + (dialect == Dialect.MYSQL ? "MEDIUMBLOB" : "BLOB")
                            + " NOT NULL, created BIGINT NOT NULL)",
                    "CREATE INDEX {prefix}kits_returns_uuid ON {prefix}kits_returns (uuid)")));

    private static final String COLUMNS =
            "name, cooldown, one_time, first_join, cost, items, icon, permission, max_uses, updated, version";

    private final Database db;
    final UserComponentType<KitUsage> usage;

    KitStore(Database db) {
        this.db = db;
        this.usage = new UserComponentType<>("kits:usage", this::loadUsage, this::saveUsage, () -> KitUsage.EMPTY);
    }

    CompletableFuture<List<Kit>> loadKits() {
        String sql = "SELECT " + COLUMNS + " FROM " + db.table("kits_kits");
        return db.query(c -> {
            List<Kit> kits = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    kits.add(new Kit(rs.getString(1), rs.getLong(2), rs.getBoolean(3), rs.getBoolean(4),
                            new BigDecimal(rs.getString(5)), rs.getBytes(6), rs.getBytes(7), rs.getString(8),
                            rs.getInt(9), rs.getLong(10), rs.getLong(11)));
                }
            }
            return kits;
        });
    }

    /** Reads one kit as stored now; empty when it no longer exists. */
    CompletableFuture<Optional<Kit>> loadKit(String name) {
        String sql = "SELECT " + COLUMNS + " FROM " + db.table("kits_kits") + " WHERE name = ?";
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    return Optional.of(new Kit(rs.getString(1), rs.getLong(2), rs.getBoolean(3), rs.getBoolean(4),
                            new BigDecimal(rs.getString(5)), rs.getBytes(6), rs.getBytes(7), rs.getString(8),
                            rs.getInt(9), rs.getLong(10), rs.getLong(11)));
                }
            }
        });
    }

    /**
     * Optimistic update: writes {@code kit} only while the stored row still has {@code kit.version()}, and bumps
     * the version. Completes with the stored kit (new version), or empty when the row changed or was deleted in
     * the meantime; in that case nothing was written.
     */
    CompletableFuture<Optional<Kit>> updateKit(Kit kit) {
        String sql = "UPDATE " + db.table("kits_kits") + " SET cooldown = ?, one_time = ?, first_join = ?, cost = ?,"
                + " items = ?, icon = ?, permission = ?, max_uses = ?, updated = ?, version = ?"
                + " WHERE name = ? AND version = ?";
        long next = KitVersions.next(kit.version());
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, kit.cooldownSeconds());
                ps.setBoolean(2, kit.oneTime());
                ps.setBoolean(3, kit.firstJoin());
                ps.setString(4, kit.cost().toPlainString());
                ps.setBytes(5, kit.items());
                ps.setBytes(6, kit.icon());
                ps.setString(7, kit.permission());
                ps.setInt(8, kit.maxUses());
                ps.setLong(9, kit.updated());
                ps.setLong(10, next);
                ps.setString(11, kit.name());
                ps.setLong(12, kit.version());
                return KitVersions.applied(ps.executeUpdate()) ? Optional.of(kit.withVersion(next)) : Optional.empty();
            }
        });
    }

    /** Inserts only when the name is free. Completes with false when it already exists. */
    CompletableFuture<Boolean> createKit(Kit kit) {
        String sql = "INSERT INTO " + db.table("kits_kits") + " (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        String check = "SELECT 1 FROM " + db.table("kits_kits") + " WHERE name = ?";
        return db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(check)) {
                ps.setString(1, kit.name());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return false;
                }
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bindKit(ps, kit);
                ps.executeUpdate();
            }
            return true;
        });
    }

    private static void bindKit(PreparedStatement ps, Kit kit) throws java.sql.SQLException {
        ps.setString(1, kit.name());
        ps.setLong(2, kit.cooldownSeconds());
        ps.setBoolean(3, kit.oneTime());
        ps.setBoolean(4, kit.firstJoin());
        ps.setString(5, kit.cost().toPlainString());
        ps.setBytes(6, kit.items());
        ps.setBytes(7, kit.icon());
        ps.setString(8, kit.permission());
        ps.setInt(9, kit.maxUses());
        ps.setLong(10, kit.updated());
        ps.setLong(11, kit.version());
    }

    /**
     * Deletes a kit and all usage records of it in one transaction, but only while the stored row still has
     * {@code version}. Completes with false when the kit was changed or removed in the meantime (nothing deleted).
     */
    CompletableFuture<Boolean> deleteKit(String name, long version) {
        return db.transaction(c -> {
            int removed;
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM " + db.table("kits_kits") + " WHERE name = ? AND version = ?")) {
                ps.setString(1, name);
                ps.setLong(2, version);
                removed = ps.executeUpdate();
            }
            if (!KitVersions.applied(removed)) return false;
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.table("kits_usage") + " WHERE kit = ?")) {
                ps.setString(1, name);
                ps.executeUpdate();
            }
            return true;
        });
    }

    /** Stores items the kit editor owes a player (serialized with {@code ItemStack#serializeItemsAsBytes}). */
    CompletableFuture<Void> addReturn(UUID uuid, byte[] items) {
        String sql = "INSERT INTO " + db.table("kits_returns") + " (uuid, items, created) VALUES (?, ?, ?)";
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setBytes(2, items);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Removes and returns every stored return of a player in one transaction. The caller owns the result: it must
     * give the items or store them again with {@link #addReturn}.
     */
    CompletableFuture<List<byte[]>> takeReturns(UUID uuid) {
        String select = "SELECT id, items FROM " + db.table("kits_returns") + " WHERE uuid = ? ORDER BY id";
        String delete = "DELETE FROM " + db.table("kits_returns") + " WHERE id = ?";
        return db.transaction(c -> {
            List<Long> ids = new ArrayList<>();
            List<byte[]> blobs = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(select)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getLong(1));
                        blobs.add(rs.getBytes(2));
                    }
                }
            }
            if (ids.isEmpty()) return List.of();
            try (PreparedStatement ps = c.prepareStatement(delete)) {
                for (long id : ids) {
                    ps.setLong(1, id);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return blobs;
        });
    }

    CompletableFuture<KitUsage> loadUsage(UUID uuid) {
        String sql = "SELECT kit, last_used, uses FROM " + db.table("kits_usage") + " WHERE uuid = ?";
        return db.query(c -> {
            Map<String, KitUsage.Claim> claims = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        claims.put(rs.getString(1), new KitUsage.Claim(rs.getLong(2), rs.getInt(3)));
                    }
                }
            }
            return claims.isEmpty() ? null : new KitUsage(claims);
        });
    }

    CompletableFuture<Void> saveUsage(UUID uuid, KitUsage value) {
        String delete = "DELETE FROM " + db.table("kits_usage") + " WHERE uuid = ?";
        String insert = "INSERT INTO " + db.table("kits_usage") + " (uuid, kit, last_used, uses) VALUES (?, ?, ?, ?)";
        return db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(delete)) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(insert)) {
                for (Map.Entry<String, KitUsage.Claim> e : value.claims().entrySet()) {
                    Database.bind(ps, uuid.toString(), e.getKey(), e.getValue().lastMillis(), e.getValue().count());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }
}
