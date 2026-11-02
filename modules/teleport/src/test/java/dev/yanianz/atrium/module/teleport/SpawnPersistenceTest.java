package dev.yanianz.atrium.module.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.yanianz.atrium.api.teleport.SpawnAdminService.Kind;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** Imported spawn points (SpawnAdminService ids) survive a restart with their world, yaw and pitch intact. */
class SpawnPersistenceTest {
    @TempDir
    Path dir;

    private Database open() throws Exception {
        Database db = new Database(new CoreConfig.Database(), dir, Executors.newVirtualThreadPerTaskExecutor(),
                LoggerFactory.getLogger("test"));
        db.migrate("teleport", TeleportStore.MIGRATIONS);
        return db;
    }

    @Test
    void spawnsSurviveRestartWithYawAndPitch() throws Exception {
        StoredLocation main = new StoredLocation("world", 12.5, 72, -340.5, -90f, 12.5f);
        StoredLocation first = new StoredLocation("world", 200.5, 80, 200.5, 45f, -5f);
        // A world that is not loaded right now is stored by name and resolved when /spawn runs.
        StoredLocation unloaded = new StoredLocation("Theia", -1306.836024430177, 77, -692.5003481315823,
                -89.920135f, -4.8099575f);
        try (Database db = open()) {
            TeleportStore store = new TeleportStore(db);
            store.saveSpawn(TeleportAdmin.spawnId(Kind.DEFAULT, null), main).get();
            store.saveSpawn(TeleportAdmin.spawnId(Kind.FIRST_JOIN, null), first).get();
            store.saveSpawn(TeleportAdmin.spawnId(Kind.WORLD, " Theia "), unloaded).get();
            // Re-importing replaces the row instead of adding one.
            store.saveSpawn(TeleportAdmin.spawnId(Kind.DEFAULT, null), main).get();
        }
        try (Database db = open()) {
            Map<String, StoredLocation> loaded = new TeleportStore(db).loadSpawns().get();
            assertEquals(Map.of(TeleportStore.DEFAULT_SPAWN, main, TeleportStore.FIRST_JOIN_SPAWN, first,
                    "world:Theia", unloaded), loaded);
        }
    }

    @Test
    void worldSpawnNeedsAWorldName() {
        assertThrows(IllegalArgumentException.class, () -> TeleportAdmin.spawnId(Kind.WORLD, " "));
        assertEquals("default", TeleportAdmin.spawnId(Kind.DEFAULT, "ignored"));
    }
}
