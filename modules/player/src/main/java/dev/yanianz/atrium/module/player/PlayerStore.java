package dev.yanianz.atrium.module.player;

import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Storage of {@link PlayerState} and {@link PlayerIdentity} in their own tables, exposed as user components. */
final class PlayerStore {
    static final List<Migration> MIGRATIONS = List.of(Migration.of(1, "player_state",
            "CREATE TABLE {prefix}player_state (uuid CHAR(36) NOT NULL PRIMARY KEY, flying BOOLEAN NOT NULL)"),
            Migration.of(2, "player_god",
                    "ALTER TABLE {prefix}player_state ADD COLUMN god BOOLEAN NOT NULL DEFAULT FALSE"),
            Migration.of(3, "player_identity",
                    "CREATE TABLE {prefix}player_identity (uuid CHAR(36) NOT NULL PRIMARY KEY,"
                            + " last_name VARCHAR(16) NOT NULL, changed_at BIGINT NOT NULL)"));

    private final Database db;
    final UserComponentType<PlayerState> component;
    final UserComponentType<PlayerIdentity> identity;

    PlayerStore(Database db) {
        this.db = db;
        this.component = new UserComponentType<>("player:state", this::load, this::save, () -> PlayerState.DEFAULT);
        this.identity = new UserComponentType<>("player:identity", this::loadIdentity, this::saveIdentity,
                () -> PlayerIdentity.EMPTY);
    }

    private CompletableFuture<PlayerState> load(UUID uuid) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT flying, god FROM " + db.table("player_state") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new PlayerState(rs.getBoolean(1), rs.getBoolean(2)) : null;
                }
            }
        });
    }

    private CompletableFuture<Void> save(UUID uuid, PlayerState state) {
        String sql = db.dialect().upsert(db.table("player_state"), "uuid, flying, god", "?, ?, ?", "uuid", "flying, god");
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, uuid.toString(), state.flying(), state.god());
                ps.executeUpdate();
            }
            return null;
        });
    }

    private CompletableFuture<PlayerIdentity> loadIdentity(UUID uuid) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT last_name, changed_at FROM " + db.table("player_identity") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new PlayerIdentity(rs.getString(1), rs.getLong(2)) : null;
                }
            }
        });
    }

    private CompletableFuture<Void> saveIdentity(UUID uuid, PlayerIdentity state) {
        String sql = db.dialect().upsert(db.table("player_identity"), "uuid, last_name, changed_at", "?, ?, ?",
                "uuid", "last_name, changed_at");
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, uuid.toString(), state.lastName(), state.changedAt());
                ps.executeUpdate();
            }
            return null;
        });
    }
}
