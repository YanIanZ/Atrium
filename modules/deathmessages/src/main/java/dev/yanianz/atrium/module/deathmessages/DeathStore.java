package dev.yanianz.atrium.module.deathmessages;

import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Dialect;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Kill and death counters in their own table. Increments are single atomic statements, so two deaths in
 * different regions cannot lose a count.
 */
final class DeathStore {
    static final List<Migration> MIGRATIONS = List.of(Migration.of(1, "deathmessages_stats",
            "CREATE TABLE {prefix}deathmessages_stats (uuid CHAR(36) NOT NULL PRIMARY KEY,"
                    + " kills BIGINT NOT NULL DEFAULT 0, deaths BIGINT NOT NULL DEFAULT 0)"));

    private final Database db;

    DeathStore(Database db) {
        this.db = db;
    }

    /** Records one death and, when the killer is another player, one kill in the same transaction. */
    CompletableFuture<Void> recordDeath(UUID victim, UUID killer) {
        return db.transaction(connection -> {
            increment(connection, victim, "deaths");
            if (killer != null && !killer.equals(victim)) {
                increment(connection, killer, "kills");
            }
            return null;
        });
    }

    private void increment(Connection connection, UUID uuid, String column) throws SQLException {
        String table = db.table("deathmessages_stats");
        int kills = column.equals("kills") ? 1 : 0;
        int deaths = column.equals("deaths") ? 1 : 0;
        String update = db.dialect() == Dialect.SQLITE
                ? " ON CONFLICT(uuid) DO UPDATE SET " + column + " = " + column + " + 1"
                : " ON DUPLICATE KEY UPDATE " + column + " = " + column + " + 1";
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + table + " (uuid, kills, deaths) VALUES (?, ?, ?)" + update)) {
            Database.bind(statement, uuid.toString(), kills, deaths);
            statement.executeUpdate();
        }
    }

    CompletableFuture<DeathStats> stats(UUID uuid) {
        return db.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT kills, deaths FROM " + db.table("deathmessages_stats") + " WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? new DeathStats(result.getLong(1), result.getLong(2)) : DeathStats.EMPTY;
                }
            }
        });
    }

    /** One leaderboard row; the name falls back to the UUID when the user row is gone. */
    record TopEntry(UUID uuid, String name, DeathStats stats) {}

    /** Players with the most kills; ties break by fewer deaths, then by name for a stable page. */
    CompletableFuture<List<TopEntry>> topByKills(int limit, int offset) {
        return db.query(connection -> {
            List<TopEntry> entries = new java.util.ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT s.uuid, u.name, s.kills, s.deaths FROM " + db.table("deathmessages_stats") + " s"
                            + " LEFT JOIN " + db.table("users") + " u ON s.uuid = u.uuid"
                            + " ORDER BY s.kills DESC, s.deaths ASC, s.uuid ASC LIMIT ? OFFSET ?")) {
                statement.setInt(1, Math.max(1, limit));
                statement.setInt(2, Math.max(0, offset));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        UUID uuid = UUID.fromString(result.getString(1));
                        String name = result.getString(2);
                        entries.add(new TopEntry(uuid, name == null ? uuid.toString() : name,
                                new DeathStats(result.getLong(3), result.getLong(4))));
                    }
                }
            }
            return List.copyOf(entries);
        });
    }
}
