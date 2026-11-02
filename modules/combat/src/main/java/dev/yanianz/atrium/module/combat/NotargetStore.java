package dev.yanianz.atrium.module.combat;

import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Persisted /notarget toggle in {@code {prefix}combat_notarget}. */
final class NotargetStore {
    static final List<Migration> MIGRATIONS = List.of(Migration.of(1, "combat_notarget",
            "CREATE TABLE {prefix}combat_notarget (uuid CHAR(36) NOT NULL PRIMARY KEY, enabled BOOLEAN NOT NULL)"));

    private final Database db;
    final UserComponentType<Boolean> component;

    NotargetStore(Database db) {
        this.db = db;
        this.component = new UserComponentType<>("combat:notarget", this::load, this::save, () -> Boolean.FALSE);
    }

    private CompletableFuture<Boolean> load(UUID uuid) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT enabled FROM " + db.table("combat_notarget") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getBoolean(1) : null;
                }
            }
        });
    }

    private CompletableFuture<Void> save(UUID uuid, Boolean enabled) {
        String sql = db.dialect().upsert(db.table("combat_notarget"), "uuid, enabled", "?, ?", "uuid", "enabled");
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, uuid.toString(), Boolean.TRUE.equals(enabled));
                ps.executeUpdate();
            }
            return null;
        });
    }
}
