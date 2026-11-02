package dev.yanianz.atrium.module.skins;

import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Two tables: {@code skins_cache} (texture per account name, with fetch time for expiry) and
 * {@code skins_choice} (the skin each player chose, backing the {@link SkinChoice} user component).
 * A malformed row is reported and treated as missing; it never breaks other rows.
 */
final class SkinStore {
    static final List<Migration> MIGRATIONS = List.of(Migration.of(1, "skin cache and choices",
            "CREATE TABLE {prefix}skins_cache (name_lower VARCHAR(16) NOT NULL PRIMARY KEY, "
                    + "name VARCHAR(16) NOT NULL, texture_value TEXT NOT NULL, texture_signature TEXT, "
                    + "fetched_at BIGINT NOT NULL)",
            "CREATE TABLE {prefix}skins_choice (uuid CHAR(36) NOT NULL PRIMARY KEY, skin_name VARCHAR(16) NOT NULL, "
                    + "texture_value TEXT NOT NULL, texture_signature TEXT, updated_at BIGINT NOT NULL)"));

    /** A cached texture of one account. */
    record CacheEntry(String name, SkinTexture texture, long fetchedAt) {}

    private final Database db;
    private final Consumer<String> warn;
    final UserComponentType<SkinChoice> component;

    SkinStore(Database db, Consumer<String> warn) {
        this.db = db;
        this.warn = warn;
        this.component = new UserComponentType<>("skins:choice", this::loadChoice, this::saveChoice,
                () -> SkinChoice.NONE);
    }

    CompletableFuture<Optional<CacheEntry>> cached(String name) {
        String key = SkinNames.key(name);
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT name, texture_value, texture_signature, fetched_at "
                    + "FROM " + db.table("skins_cache") + " WHERE name_lower = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<CacheEntry>empty();
                    }
                    String value = rs.getString(2);
                    if (!SkinTexture.wellFormed(value)) {
                        warn.accept("Ignoring a malformed cached skin texture for '" + key + "'");
                        return Optional.<CacheEntry>empty();
                    }
                    return Optional.of(new CacheEntry(rs.getString(1), new SkinTexture(value, rs.getString(3)),
                            rs.getLong(4)));
                }
            }
        });
    }

    CompletableFuture<Void> putCache(CacheEntry entry) {
        String key = SkinNames.key(entry.name());
        return db.transaction(c -> {
            try (PreparedStatement delete = c.prepareStatement(
                    "DELETE FROM " + db.table("skins_cache") + " WHERE name_lower = ?")) {
                delete.setString(1, key);
                delete.executeUpdate();
            }
            try (PreparedStatement insert = c.prepareStatement("INSERT INTO " + db.table("skins_cache")
                    + " (name_lower, name, texture_value, texture_signature, fetched_at) VALUES (?, ?, ?, ?, ?)")) {
                bind(insert, key, entry.name(), entry.texture(), entry.fetchedAt());
                insert.executeUpdate();
            }
            return null;
        });
    }

    /** Deletes cache rows fetched before {@code olderThan} (epoch millis). */
    CompletableFuture<Integer> purge(long olderThan) {
        return db.update("DELETE FROM {prefix}skins_cache WHERE fetched_at < ?", olderThan);
    }

    private CompletableFuture<SkinChoice> loadChoice(UUID uuid) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT skin_name, texture_value, texture_signature, "
                    + "updated_at FROM " + db.table("skins_choice") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    String value = rs.getString(2);
                    if (!SkinTexture.wellFormed(value)) {
                        warn.accept("Ignoring a malformed stored skin of " + uuid);
                        return null;
                    }
                    return new SkinChoice(rs.getString(1), new SkinTexture(value, rs.getString(3)), rs.getLong(4));
                }
            }
        });
    }

    private CompletableFuture<Void> saveChoice(UUID uuid, SkinChoice choice) {
        return db.transaction(c -> {
            deleteChoice(c, uuid);
            if (choice.custom()) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.table("skins_choice")
                        + " (uuid, skin_name, texture_value, texture_signature, updated_at) VALUES (?, ?, ?, ?, ?)")) {
                    bind(ps, uuid.toString(), choice.skinName(), choice.texture(), choice.updatedAt());
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    /** Binds (key, name, value, signature, time); a missing signature is bound as SQL NULL. */
    private static void bind(PreparedStatement ps, String key, String name, SkinTexture texture, long time)
            throws SQLException {
        ps.setString(1, key);
        ps.setString(2, name);
        ps.setString(3, texture.value());
        if (texture.signature() == null) {
            ps.setNull(4, Types.VARCHAR);
        } else {
            ps.setString(4, texture.signature());
        }
        ps.setLong(5, time);
    }

    private void deleteChoice(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM " + db.table("skins_choice") + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
    }
}
