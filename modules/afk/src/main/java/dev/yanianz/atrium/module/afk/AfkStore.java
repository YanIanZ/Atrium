package dev.yanianz.atrium.module.afk;

import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Persists {@link AfkProfile}; AFK state itself is never stored because it must not survive a restart. */
final class AfkStore {
    static final List<Migration> MIGRATIONS = List.of(Migration.of(1, "afk profile",
            "CREATE TABLE {prefix}afk_profile (uuid CHAR(36) NOT NULL PRIMARY KEY, "
                    + "total_seconds BIGINT NOT NULL, sessions BIGINT NOT NULL)"));

    private final Database db;
    final UserComponentType<AfkProfile> component;

    AfkStore(Database db) {
        this.db = db;
        this.component = new UserComponentType<>("afk:profile", this::load, this::save, () -> AfkProfile.EMPTY);
    }

    private CompletableFuture<AfkProfile> load(UUID uuid) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT total_seconds, sessions FROM " + db.table("afk_profile") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new AfkProfile(rs.getLong(1), rs.getLong(2)) : null;
                }
            }
        });
    }

    private CompletableFuture<Void> save(UUID uuid, AfkProfile profile) {
        String sql = db.dialect().upsert(db.table("afk_profile"), "uuid, total_seconds, sessions", "?, ?, ?",
                "uuid", "total_seconds, sessions");
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, uuid.toString(), profile.totalSeconds(), profile.sessions());
                ps.executeUpdate();
            }
            return null;
        });
    }
}
