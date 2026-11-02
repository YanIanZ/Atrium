package dev.yanianz.atrium.module.itemtools;

import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.core.storage.Database;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Per-player item options, stored as a user component in {@code {prefix}itemtools_prefs}. Only options a
 * player asked for are on: every default is {@code false}.
 */
final class ItemToolsPrefsStore {
    /** Immutable preference snapshot. */
    record Prefs(boolean durabilityWarning) {
        static final Prefs DEFAULTS = new Prefs(false);

        Prefs withDurabilityWarning(boolean value) {
            return new Prefs(value);
        }
    }

    private final Database db;
    final UserComponentType<Prefs> component;

    ItemToolsPrefsStore(Database db) {
        this.db = db;
        this.component = new UserComponentType<>("itemtools:prefs", this::load, this::save, () -> Prefs.DEFAULTS);
    }

    private CompletableFuture<Prefs> load(UUID uuid) {
        return db.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT durability_warning FROM " + db.table("itemtools_prefs") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Prefs(rs.getBoolean(1)) : null;
                }
            }
        });
    }

    private CompletableFuture<Void> save(UUID uuid, Prefs prefs) {
        String sql = db.dialect().upsert(db.table("itemtools_prefs"), "uuid, durability_warning", "?, ?",
                "uuid", "durability_warning");
        return db.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                Database.bind(ps, uuid.toString(), prefs.durabilityWarning());
                ps.executeUpdate();
            }
            return null;
        });
    }
}
