package dev.yanianz.atrium.module.nick;

import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Nickname storage in its own table, plus the {@link NickState} user component. */
final class NickStore {
    static final List<Migration> MIGRATIONS = List.of(Migration.of(1, "nickname",
            "CREATE TABLE {prefix}nick (uuid CHAR(36) NOT NULL PRIMARY KEY, nickname TEXT NOT NULL, "
                    + "plain VARCHAR(64) NOT NULL, plain_lower VARCHAR(64) NOT NULL, updated_at BIGINT NOT NULL)",
            "CREATE UNIQUE INDEX {prefix}nick_plain_lower ON {prefix}nick (plain_lower)"));

    enum ClaimResult {
        CLAIMED,
        TAKEN
    }

    private final Database db;
    final UserComponentType<NickState> component;

    NickStore(Database db) {
        this.db = db;
        this.component = new UserComponentType<>("nick:state", this::load, this::save, () -> NickState.EMPTY);
    }

    /**
     * Takes a nickname for a player in one transaction. The unique index on {@code plain_lower} is the
     * authority; the pre-check merely produces a clear failure instead of a constraint error.
     */
    CompletableFuture<ClaimResult> claim(UUID uuid, NickState state) {
        String lower = NickLogic.canonical(state.plain());
        return db.transaction(c -> {
            delete(c, uuid);
            if (owner(c, lower) != null) {
                throw new NickTakenException();
            }
            insert(c, uuid, state);
            return ClaimResult.CLAIMED;
        }).handle((result, error) -> {
            if (error == null) {
                return result;
            }
            Throwable cause = error;
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof NickTakenException) {
                return ClaimResult.TAKEN;
            }
            throw error instanceof CompletionException failure ? failure : new CompletionException(error);
        });
    }

    CompletableFuture<Void> release(UUID uuid) {
        return db.update("DELETE FROM {prefix}nick WHERE uuid = ?", uuid.toString()).thenApply(ignored -> null);
    }

    CompletableFuture<Optional<UUID>> findOwner(String lower) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT uuid FROM " + db.table("nick") + " WHERE plain_lower = ?")) {
                ps.setString(1, lower);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<UUID>empty();
                    }
                    try {
                        return Optional.of(UUID.fromString(rs.getString(1)));
                    } catch (IllegalArgumentException e) {
                        return Optional.<UUID>empty();
                    }
                }
            }
        });
    }

    private CompletableFuture<NickState> load(UUID uuid) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT nickname, plain, updated_at FROM "
                    + db.table("nick") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next()
                            ? new NickState(rs.getString(1), rs.getString(2), rs.getLong(3))
                            : null;
                }
            }
        });
    }

    private CompletableFuture<Void> save(UUID uuid, NickState state) {
        return db.transaction(c -> {
            delete(c, uuid);
            if (state.hasNick()) {
                insert(c, uuid, state);
            }
            return null;
        });
    }

    private void delete(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM " + db.table("nick") + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
    }

    private void insert(Connection connection, UUID uuid, NickState state) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO " + db.table("nick")
                + " (uuid, nickname, plain, plain_lower, updated_at) VALUES (?, ?, ?, ?, ?)")) {
            Database.bind(ps, uuid.toString(), state.nick(), state.plain(),
                    NickLogic.canonical(state.plain()), state.updatedAt());
            ps.executeUpdate();
        }
    }

    private String owner(Connection connection, String lower) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT uuid FROM " + db.table("nick") + " WHERE plain_lower = ?")) {
            ps.setString(1, lower);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static final class NickTakenException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
