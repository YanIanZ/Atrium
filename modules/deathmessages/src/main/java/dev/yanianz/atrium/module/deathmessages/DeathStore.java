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
}
