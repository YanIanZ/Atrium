package dev.yanianz.atrium.module.seen;

import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Last logout location per player in {@code {prefix}seen_logout}, exposed as a user component. */
final class SeenStore {
    static final List<Migration> MIGRATIONS = List.of(Migration.of(1, "seen_logout",
            "CREATE TABLE {prefix}seen_logout (uuid CHAR(36) NOT NULL PRIMARY KEY, world VARCHAR(128) NOT NULL,"
                    + " x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw FLOAT NOT NULL,"
                    + " pitch FLOAT NOT NULL, logout_at BIGINT NOT NULL)"));

    private final Database db;
    final UserComponentType<LogoutLocation> component;

    SeenStore(Database db) {
        this.db = db;
        this.component = new UserComponentType<>("seen:logout", this::load, this::save, () -> LogoutLocation.NONE);
    }

    private CompletableFuture<LogoutLocation> load(UUID uuid) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT world, x, y, z, yaw, pitch, logout_at FROM "
                    + db.table("seen_logout") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new LogoutLocation(rs.getString(1), rs.getDouble(2), rs.getDouble(3),
                            rs.getDouble(4), rs.getFloat(5), rs.getFloat(6), rs.getLong(7)) : null;
                }
            }
        });
    }

    private CompletableFuture<Void> save(UUID uuid, LogoutLocation value) {
        if (!value.isKnown()) {
            return CompletableFuture.completedFuture(null);
        }
        String sql = db.dialect().upsert(db.table("seen_logout"), "uuid, world, x, y, z, yaw, pitch, logout_at",
                "?, ?, ?, ?, ?, ?, ?, ?", "uuid", "world, x, y, z, yaw, pitch, logout_at");
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, uuid.toString(), value.world(), value.x(), value.y(), value.z(), value.yaw(),
                        value.pitch(), value.loggedOutAt());
                ps.executeUpdate();
            }
            return null;
        });
    }
}
