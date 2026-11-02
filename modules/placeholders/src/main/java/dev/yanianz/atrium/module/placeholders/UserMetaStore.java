package dev.yanianz.atrium.module.placeholders;

import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Per-player custom metadata managed by {@code /usermeta} and read by {@code %atrium_user_meta_*%}. */
final class UserMetaStore {
    static final List<Migration> MIGRATIONS = List.of(Migration.of(1, "placeholder user metadata",
            "CREATE TABLE {prefix}placeholders_usermeta (uuid CHAR(36) NOT NULL, meta_key VARCHAR(64) NOT NULL, "
                    + "meta_value TEXT NOT NULL, updated BIGINT NOT NULL, PRIMARY KEY (uuid, meta_key))"));

    record Account(UUID uuid, String name) {}

    private final Database db;

    UserMetaStore(Database db) {
        this.db = db;
    }

    /**
     * Resolves an account without loading it into the user cache. {@code /usermeta} must not make a later
     * {@code /removeuser} refuse the same offline account as "still cached".
     */
    CompletableFuture<Optional<Account>> account(String name) {
        String lower = name == null ? "" : name.strip().toLowerCase(java.util.Locale.ROOT);
        return db.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT uuid, name FROM " + db.table("users")
                    + " WHERE name_lower = ? ORDER BY last_seen DESC")) {
                ps.setString(1, lower);
                try (ResultSet rows = ps.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.<Account>empty();
                    }
                    return Optional.of(new Account(UUID.fromString(rows.getString(1)), rows.getString(2)));
                }
            }
        });
    }

    CompletableFuture<Map<String, String>> load(UUID uuid) {
        return db.query(connection -> {
            Map<String, String> values = new LinkedHashMap<>();
            try (PreparedStatement ps = connection.prepareStatement("SELECT meta_key, meta_value FROM "
                    + db.table("placeholders_usermeta") + " WHERE uuid = ? ORDER BY meta_key")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rows = ps.executeQuery()) {
                    while (rows.next()) {
                        values.put(rows.getString(1), rows.getString(2));
                    }
                }
            }
            return Map.copyOf(values);
        });
    }

    CompletableFuture<Optional<String>> get(UUID uuid, String key) {
        return db.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT meta_value FROM "
                    + db.table("placeholders_usermeta") + " WHERE uuid = ? AND meta_key = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, key);
                try (ResultSet rows = ps.executeQuery()) {
                    return rows.next() ? Optional.of(rows.getString(1)) : Optional.<String>empty();
                }
            }
        });
    }

    CompletableFuture<Void> set(UUID uuid, String key, String value) {
        String table = db.table("placeholders_usermeta");
        String sql = db.dialect().upsert(table, "uuid, meta_key, meta_value, updated", "?, ?, ?, ?",
                "uuid, meta_key", "meta_value, updated");
        return db.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                Database.bind(ps, uuid.toString(), key, value, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    CompletableFuture<Boolean> remove(UUID uuid, String key) {
        return db.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM "
                    + db.table("placeholders_usermeta") + " WHERE uuid = ? AND meta_key = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, key);
                return ps.executeUpdate() > 0;
            }
        });
    }

    CompletableFuture<Integer> clear(UUID uuid) {
        return db.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM "
                    + db.table("placeholders_usermeta") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                return ps.executeUpdate();
            }
        });
    }

    /**
     * Adds to a numeric value in one transaction, so two concurrent increments cannot both read the old
     * value. Empty when the stored or supplied value is not numeric.
     */
    CompletableFuture<Optional<String>> increment(UUID uuid, String key, String amount) {
        String table = db.table("placeholders_usermeta");
        return db.transaction(connection -> {
            String current = null;
            try (PreparedStatement ps = connection.prepareStatement("SELECT meta_value FROM " + table
                    + " WHERE uuid = ? AND meta_key = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, key);
                try (ResultSet rows = ps.executeQuery()) {
                    if (rows.next()) {
                        current = rows.getString(1);
                    }
                }
            }
            Optional<String> next = UserMetaValues.increment(current, amount);
            if (next.isEmpty()) {
                return Optional.<String>empty();
            }
            String sql = db.dialect().upsert(table, "uuid, meta_key, meta_value, updated", "?, ?, ?, ?",
                    "uuid, meta_key", "meta_value, updated");
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                Database.bind(ps, uuid.toString(), key, next.get(), System.currentTimeMillis());
                ps.executeUpdate();
            }
            return next;
        });
    }
}
