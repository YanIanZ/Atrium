package dev.yanianz.atrium.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.yanianz.atrium.core.config.CoreConfig;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class DatabaseTest {
    @TempDir
    Path dir;

    private Database open() {
        return new Database(new CoreConfig.Database(), dir, Executors.newVirtualThreadPerTaskExecutor(),
                LoggerFactory.getLogger("test"));
    }

    @Test
    void migrationsRunOnceAndInOrder() throws Exception {
        try (Database db = open()) {
            List<Migration> v1 = List.of(Migration.of(1, "t", "CREATE TABLE {prefix}t (id INT PRIMARY KEY, v TEXT)"));
            db.migrate("test", v1);
            db.migrate("test", v1); // second run must be a no-op
            db.migrate("test", List.of(v1.getFirst(), Migration.of(2, "col", "ALTER TABLE {prefix}t ADD COLUMN w INT")));
            int version = db.query(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT version FROM " + db.table("schema_versions") + " WHERE namespace = 'test'");
                        ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }).get();
            assertEquals(2, version);
        }
    }

    @Test
    void failedTransactionRollsBackAndFails() throws Exception {
        try (Database db = open()) {
            db.migrate("tx", List.of(Migration.of(1, "t", "CREATE TABLE {prefix}tx (id INT PRIMARY KEY)")));
            var future = db.transaction(c -> {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.table("tx") + " VALUES (1)")) {
                    ps.executeUpdate();
                }
                throw new java.sql.SQLException("boom");
            });
            assertThrows(ExecutionException.class, future::get);
            int rows = db.query(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + db.table("tx"));
                        ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }).get();
            assertEquals(0, rows);
        }
    }

    @Test
    void upsertSyntaxPerDialect() {
        assertEquals("INSERT INTO t (a, b) VALUES (?, ?) ON CONFLICT(a) DO UPDATE SET b = excluded.b",
                Dialect.SQLITE.upsert("t", "a, b", "?, ?", "a", "b"));
        assertEquals("INSERT INTO t (a, b) VALUES (?, ?) ON DUPLICATE KEY UPDATE b = VALUES(b)",
                Dialect.MYSQL.upsert("t", "a, b", "?, ?", "a", "b"));
    }
}
