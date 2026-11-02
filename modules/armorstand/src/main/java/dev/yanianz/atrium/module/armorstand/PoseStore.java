package dev.yanianz.atrium.module.armorstand;

import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/** Named poses per player in {@code {prefix}armorstand_poses}. */
final class PoseStore {
    static final List<Migration> MIGRATIONS = List.of(Migration.of(1, "armorstand_poses",
            "CREATE TABLE {prefix}armorstand_poses (owner CHAR(36) NOT NULL, name VARCHAR(32) NOT NULL,"
                    + " pose VARCHAR(512) NOT NULL, updated_at BIGINT NOT NULL, PRIMARY KEY (owner, name))"));

    static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");

    enum SaveResult { CREATED, REPLACED, LIMIT }

    private final Database db;

    PoseStore(Database db) {
        this.db = db;
    }

    static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** Saves atomically: the limit counts existing poses, replacing an existing name is always allowed. */
    CompletableFuture<SaveResult> save(UUID owner, String name, Pose pose, int limit) {
        String table = db.table("armorstand_poses");
        String upsert = db.dialect().upsert(table, "owner, name, pose, updated_at", "?, ?, ?, ?", "owner, name",
                "pose, updated_at");
        return db.transaction(c -> {
            boolean exists;
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + table + " WHERE owner = ? AND name = ?")) {
                Database.bind(ps, owner.toString(), name);
                try (ResultSet rs = ps.executeQuery()) {
                    exists = rs.next();
                }
            }
            if (!exists) {
                try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE owner = ?")) {
                    ps.setString(1, owner.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next() && rs.getInt(1) >= limit) {
                            return SaveResult.LIMIT;
                        }
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(upsert)) {
                Database.bind(ps, owner.toString(), name, pose.encode(), System.currentTimeMillis());
                ps.executeUpdate();
            }
            return exists ? SaveResult.REPLACED : SaveResult.CREATED;
        });
    }

    CompletableFuture<Optional<Pose>> load(UUID owner, String name) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT pose FROM " + db.table("armorstand_poses") + " WHERE owner = ? AND name = ?")) {
                Database.bind(ps, owner.toString(), name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(Pose.decode(rs.getString(1))) : Optional.empty();
                }
            }
        });
    }

    CompletableFuture<Boolean> delete(UUID owner, String name) {
        return db.update("DELETE FROM {prefix}armorstand_poses WHERE owner = ? AND name = ?", owner.toString(), name)
                .thenApply(n -> n > 0);
    }

    CompletableFuture<List<String>> names(UUID owner) {
        return db.query(c -> {
            List<String> names = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT name FROM " + db.table("armorstand_poses") + " WHERE owner = ? ORDER BY name")) {
                ps.setString(1, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        names.add(rs.getString(1));
                    }
                }
            }
            return names;
        });
    }
}
