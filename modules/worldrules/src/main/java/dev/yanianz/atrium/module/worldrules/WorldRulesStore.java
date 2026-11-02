package dev.yanianz.atrium.module.worldrules;

import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Persisted per-player time settings in {@code {prefix}worldrules_ptime}. */
final class WorldRulesStore {
    static final List<Migration> MIGRATIONS = List.of(Migration.of(1, "worldrules_ptime",
            "CREATE TABLE {prefix}worldrules_ptime (uuid CHAR(36) NOT NULL PRIMARY KEY, "
                    + "ticks BIGINT NOT NULL, relative BOOLEAN NOT NULL, active BOOLEAN NOT NULL)"));

    /**
     * A player time. {@code relative} means the ticks are an offset to the world time (the world keeps
     * moving); otherwise the time is frozen at {@code ticks}. {@code active} is false for "use world time".
     */
    record PlayerTime(boolean active, long ticks, boolean relative) {
        static final PlayerTime NOT_SET = new PlayerTime(false, 0L, false);
    }

    private final Database db;
    final UserComponentType<PlayerTime> component;

    WorldRulesStore(Database db) {
        this.db = db;
        this.component = new UserComponentType<>("worldrules:ptime", this::load, this::save, () -> PlayerTime.NOT_SET);
    }

    private CompletableFuture<PlayerTime> load(UUID uuid) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ticks, relative, active FROM " + db.table("worldrules_ptime") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new PlayerTime(rs.getBoolean(3), rs.getLong(1), rs.getBoolean(2)) : null;
                }
            }
        });
    }

    private CompletableFuture<Void> save(UUID uuid, PlayerTime value) {
        String sql = db.dialect().upsert(db.table("worldrules_ptime"),
                "uuid, ticks, relative, active", "?, ?, ?, ?", "uuid", "ticks, relative, active");
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, uuid.toString(), value.ticks(), value.relative(), value.active());
                ps.executeUpdate();
            }
            return null;
        });
    }
}
