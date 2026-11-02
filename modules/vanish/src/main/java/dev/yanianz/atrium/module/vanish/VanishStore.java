package dev.yanianz.atrium.module.vanish;

import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Storage of {@link VanishState} in {@code {prefix}vanish_state}, exposed as a user component. */
final class VanishStore {
    static final List<Migration> MIGRATIONS = List.of(Migration.of(1, "vanish_state",
            "CREATE TABLE {prefix}vanish_state (uuid CHAR(36) NOT NULL PRIMARY KEY, vanished BOOLEAN NOT NULL,"
                    + " pickup BOOLEAN NOT NULL)"));

    private final Database db;
    final UserComponentType<VanishState> component;

    VanishStore(Database db) {
        this.db = db;
        this.component = new UserComponentType<>("vanish:state", this::load, this::save, () -> VanishState.DEFAULT);
    }

    CompletableFuture<VanishState> load(UUID uuid) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT vanished, pickup FROM " + db.table("vanish_state") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new VanishState(rs.getBoolean(1), rs.getBoolean(2)) : null;
                }
            }
        });
    }

    private CompletableFuture<Void> save(UUID uuid, VanishState state) {
        String sql = db.dialect().upsert(db.table("vanish_state"), "uuid, vanished, pickup", "?, ?, ?", "uuid",
                "vanished, pickup");
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, uuid.toString(), state.vanished(), state.pickup());
                ps.executeUpdate();
            }
            return null;
        });
    }
}
