package dev.yanianz.atrium.module.player;

import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Storage of {@link PlayerState} in its own table, exposed as a user component. */
final class PlayerStore {
    static final List<Migration> MIGRATIONS = List.of(Migration.of(1, "player_state",
            "CREATE TABLE {prefix}player_state (uuid CHAR(36) NOT NULL PRIMARY KEY, flying BOOLEAN NOT NULL)"),
            Migration.of(2, "player_god",
                    "ALTER TABLE {prefix}player_state ADD COLUMN god BOOLEAN NOT NULL DEFAULT FALSE"));

    private final Database db;
    final UserComponentType<PlayerState> component;

    PlayerStore(Database db) {
        this.db = db;
        this.component = new UserComponentType<>("player:state", this::load, this::save, () -> PlayerState.DEFAULT);
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
}
