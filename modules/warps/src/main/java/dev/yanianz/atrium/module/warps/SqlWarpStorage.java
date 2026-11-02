package dev.yanianz.atrium.module.warps;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Warps table {@code {prefix}warps_warps}. */
final class SqlWarpStorage implements WarpStorage {
    static final List<Migration> MIGRATIONS = List.of(Migration.of(1, "warps_warps",
            "CREATE TABLE {prefix}warps_warps (name_lower VARCHAR(32) NOT NULL PRIMARY KEY, name VARCHAR(32) NOT NULL, "
                    + "category VARCHAR(32) NOT NULL, world VARCHAR(128) NOT NULL, x DOUBLE NOT NULL, y DOUBLE NOT NULL, "
                    + "z DOUBLE NOT NULL, yaw FLOAT NOT NULL, pitch FLOAT NOT NULL, creator CHAR(36), created BIGINT NOT NULL)"),
            Migration.of(2, "warps custom permission",
                    "ALTER TABLE {prefix}warps_warps ADD COLUMN permission VARCHAR(128)"));

    private static final String COLUMNS =
            "name_lower, name, category, world, x, y, z, yaw, pitch, creator, created, permission";

    private final Database db;

    SqlWarpStorage(Database db) {
        this.db = db;
    }

    @Override
    public CompletableFuture<List<Warp>> loadAll() {
        return db.query(c -> {
            List<Warp> warps = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT name, category, world, x, y, z, yaw, pitch, creator, created, permission FROM "
                    + db.table("warps_warps")); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String creator = rs.getString(9);
                    warps.add(new Warp(rs.getString(1), rs.getString(2),
                            new StoredLocation(rs.getString(3), rs.getDouble(4), rs.getDouble(5), rs.getDouble(6),
                                    rs.getFloat(7), rs.getFloat(8)),
                            creator == null ? null : UUID.fromString(creator), rs.getLong(10), rs.getString(11)));
                }
            }
            return warps;
        });
    }

    @Override
    public CompletableFuture<Void> upsert(Warp warp) {
        String sql = db.dialect().upsert(db.table("warps_warps"), COLUMNS,
                "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?", "name_lower",
                "name, category, world, x, y, z, yaw, pitch, creator, created, permission");
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, warp.key(), warp);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Boolean> delete(String key) {
        return db.update("DELETE FROM {prefix}warps_warps WHERE name_lower = ?", key).thenApply(n -> n > 0);
    }

    @Override
    public CompletableFuture<Boolean> rename(String oldKey, Warp renamed) {
        return db.update("UPDATE {prefix}warps_warps SET name_lower = ?, name = ? WHERE name_lower = ?",
                renamed.key(), renamed.name(), oldKey).thenApply(n -> n > 0);
    }

    private static void bind(PreparedStatement ps, String key, Warp warp) throws java.sql.SQLException {
        StoredLocation l = warp.location();
        Database.bind(ps, key, warp.name(), warp.category(), l.world(), l.x(), l.y(), l.z(), l.yaw(), l.pitch(),
                warp.creator() == null ? null : warp.creator().toString(), warp.createdAt(), warp.storedPermission());
    }
}
