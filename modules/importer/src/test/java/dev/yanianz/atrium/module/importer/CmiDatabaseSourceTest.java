package dev.yanianz.atrium.module.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CmiDatabaseSourceTest {
    @TempDir
    Path directory;

    @Test
    void discoversTablesColumnsAndPlaytime() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Path database = directory.resolve("cmi.sqlite.db");
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid TEXT, "
                    + "username TEXT, nickname TEXT, Homes TEXT, Economy TEXT)");
            statement.execute("CREATE TABLE playtime (player_id INTEGER, date INTEGER, h0 BIGINT, h1 BIGINT)");
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO users (player_uuid, username, nickname, Homes, Economy) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, alice.toString());
                ps.setString(2, "Alice");
                ps.setString(3, "&aAlice");
                ps.setString(4, "home$-p$-f$-AIR%%world:1:2:3:0:0");
                ps.setString(5, "default%%123.45");
                ps.executeUpdate();
                ps.setString(1, bob.toString());
                ps.setString(2, "Bob");
                ps.setString(3, null);
                ps.setString(4, null);
                ps.setString(5, "{\"default\":7}");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO playtime (player_id, date, h0, h1) VALUES (?, ?, ?, ?)")) {
                ps.setLong(1, 1);
                ps.setInt(2, 20260101);
                ps.setLong(3, 60_000);
                ps.setLong(4, 30_000);
                ps.executeUpdate();
            }
        }
        CmiConnectionConfig config = new CmiConnectionConfig(CmiConnectionConfig.Kind.SQLITE, database,
                "", 0, "", "", "", "");
        try (CmiDatabaseSource source = CmiDatabaseSource.open(config)) {
            assertTrue(source.hasHomesColumn());
            assertTrue(source.hasNickColumn());
            assertTrue(source.hasEconomyColumn());
            assertTrue(source.hasPlaytimeTable());
            assertEquals(2, source.userCount());
            List<CmiUserRecord> users = source.users(0, 10);
            assertEquals(2, users.size());
            assertEquals(alice, users.get(0).uuid());
            assertEquals("Alice", users.get(0).name());
            assertEquals("&aAlice", users.get(0).nickname());
            assertEquals(bob, users.get(1).uuid());
            assertEquals(1, source.users(1, 1).size());
            Map<Long, Long> playtime = source.playtimeSeconds(List.of(1L, 2L));
            assertEquals(90L, playtime.get(1L));
            assertFalse(playtime.containsKey(2L));
        }
    }
}
