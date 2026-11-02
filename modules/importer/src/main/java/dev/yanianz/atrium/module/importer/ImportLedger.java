package dev.yanianz.atrium.module.importer;

import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Remembers which player was already imported from which source and category. Balances and playtime are
 * absolute values: re-applying them on a second run would mint money or reset playtime the player has
 * spent or earned since, so a marked UUID is skipped unless the admin passes {@code --overwrite}.
 *
 * <p>A marker is written <em>before</em> the value itself. A crash between the two then leaves a marker
 * without a value (re-run with {@code --overwrite} to repair), never a value without a marker (which a
 * re-run would apply a second time).
 */
final class ImportLedger {
    static final List<Migration> MIGRATIONS = List.of(Migration.of(1, "imported player markers",
            "CREATE TABLE {prefix}importer_done (source VARCHAR(32) NOT NULL, category VARCHAR(32) NOT NULL,"
                    + " uuid CHAR(36) NOT NULL, imported_at BIGINT NOT NULL, PRIMARY KEY (source, category, uuid))"));

    private final Database db;

    ImportLedger(Database db) {
        this.db = db;
    }

    /** Every UUID already imported from {@code source} for {@code category}. */
    CompletableFuture<Set<UUID>> load(String source, ImportCategory category) {
        String sql = "SELECT uuid FROM " + db.table("importer_done") + " WHERE source = ? AND category = ?";
        return db.query(c -> {
            Set<UUID> done = new HashSet<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, source, category.key());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        try {
                            done.add(UUID.fromString(rs.getString(1).trim()));
                        } catch (IllegalArgumentException ignored) {
                            // A hand-edited row cannot match any player; it is simply not a marker.
                        }
                    }
                }
            }
            return done;
        });
    }

    CompletableFuture<Integer> mark(String source, ImportCategory category, UUID uuid) {
        // REPLACE INTO is understood by both SQLite and MySQL/MariaDB.
        return db.update("REPLACE INTO {prefix}importer_done (source, category, uuid, imported_at) VALUES (?, ?, ?, ?)",
                source, category.key(), uuid.toString(), System.currentTimeMillis());
    }

    CompletableFuture<Integer> unmark(String source, ImportCategory category, UUID uuid) {
        return db.update("DELETE FROM {prefix}importer_done WHERE source = ? AND category = ? AND uuid = ?",
                source, category.key(), uuid.toString());
    }
}
