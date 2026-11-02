package dev.yanianz.atrium.module.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CmiConnectionConfigTest {
    @TempDir
    Path directory;

    private void copyFixture(String fixture, Path target) throws Exception {
        Path source = Path.of(getClass().getResource("/fixtures/" + fixture).toURI());
        Files.copy(source, target);
    }

    @Test
    void readsMysqlSettingsWithoutLeakingThePassword() throws Exception {
        copyFixture("cmi-config-mysql.yml", directory.resolve("config.yml"));
        CmiConnectionConfig config = CmiConnectionConfig.read(directory);
        assertEquals(CmiConnectionConfig.Kind.MYSQL, config.kind());
        assertEquals("db.example", config.host());
        assertEquals(3307, config.port());
        assertEquals("cmi_data", config.database());
        assertEquals("importer", config.username());
        assertEquals("cmi_", config.tablePrefix());
        assertFalse(config.describe().contains("super-secret"));
        assertTrue(config.jdbcUrl().startsWith("jdbc:mariadb://db.example:3307/cmi_data"));
    }

    @Test
    void fallsBackToTheSqliteFile() throws Exception {
        copyFixture("cmi-config-sqlite.yml", directory.resolve("config.yml"));
        Files.createFile(directory.resolve("cmi.sqlite.db"));
        CmiConnectionConfig config = CmiConnectionConfig.read(directory);
        assertEquals(CmiConnectionConfig.Kind.SQLITE, config.kind());
        assertTrue(config.sqliteFile().endsWith("cmi.sqlite.db"));
        assertTrue(config.jdbcUrl().startsWith("jdbc:sqlite:"));
    }

    @Test
    void missingFolderIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CmiConnectionConfig.read(directory.resolve("missing")));
    }
}
