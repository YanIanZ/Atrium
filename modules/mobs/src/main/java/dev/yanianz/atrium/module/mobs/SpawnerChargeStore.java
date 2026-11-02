package dev.yanianz.atrium.module.mobs;

import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** {@link SpawnerChargeState} in {@code {prefix}mobs_spawner_charges}, exposed as a user component. */
final class SpawnerChargeStore {
    static final List<Migration> MIGRATIONS = List.of(Migration.of(1, "spawner charges",
            "CREATE TABLE {prefix}mobs_spawner_charges (uuid CHAR(36) NOT NULL PRIMARY KEY, "
                    + "charges INT NOT NULL, last_give BIGINT NOT NULL)"));

    private final Database db;
    final UserComponentType<SpawnerChargeState> component;

    SpawnerChargeStore(Database db) {
        this.db = db;
        this.component = new UserComponentType<>("mobs:spawner-charges", this::load, this::save,
                () -> new SpawnerChargeState(0, 0));
    }

    private CompletableFuture<SpawnerChargeState> load(UUID uuid) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT charges, last_give FROM " + db.table("mobs_spawner_charges") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new SpawnerChargeState(rs.getInt(1), rs.getLong(2)) : null;
                }
            }
        });
    }

    private CompletableFuture<Void> save(UUID uuid, SpawnerChargeState state) {
        String sql = db.dialect().upsert(db.table("mobs_spawner_charges"), "uuid, charges, last_give", "?, ?, ?",
                "uuid", "charges, last_give");
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, uuid.toString(), state.charges(), state.lastGiveMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }
}
