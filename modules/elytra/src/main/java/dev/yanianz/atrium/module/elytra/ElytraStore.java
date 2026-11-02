package dev.yanianz.atrium.module.elytra;

import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** {@link ElytraPrefs} in {@code {prefix}elytra_prefs}, exposed as a user component. */
final class ElytraStore {
    static final List<Migration> MIGRATIONS = List.of(Migration.of(1, "elytra_prefs",
            "CREATE TABLE {prefix}elytra_prefs (uuid CHAR(36) NOT NULL PRIMARY KEY, boost BOOLEAN NOT NULL, launch BOOLEAN NOT NULL)"));

    private final Database db;
    final UserComponentType<ElytraPrefs> component;

    ElytraStore(Database db) {
        this.db = db;
        this.component = new UserComponentType<>("elytra:prefs", this::load, this::save, () -> ElytraPrefs.DEFAULT);
    }

    private CompletableFuture<ElytraPrefs> load(UUID uuid) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT boost, launch FROM " + db.table("elytra_prefs") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new ElytraPrefs(rs.getBoolean(1), rs.getBoolean(2)) : null;
                }
            }
        });
    }

    private CompletableFuture<Void> save(UUID uuid, ElytraPrefs prefs) {
        String sql = db.dialect().upsert(db.table("elytra_prefs"), "uuid, boost, launch", "?, ?, ?", "uuid", "boost, launch");
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, uuid.toString(), prefs.boost(), prefs.launch());
                ps.executeUpdate();
            }
            return null;
        });
    }
}
