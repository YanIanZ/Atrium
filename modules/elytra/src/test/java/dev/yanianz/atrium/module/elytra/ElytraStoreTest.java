package dev.yanianz.atrium.module.elytra;

import static org.junit.jupiter.api.Assertions.*;

import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class ElytraStoreTest {
    @TempDir Path directory;

    @Test void roundTripsPrefs() throws Exception {
        try (var database = new Database(new CoreConfig.Database(), directory, Runnable::run, LoggerFactory.getLogger(getClass()))) {
            database.migrate("elytra", ElytraStore.MIGRATIONS);
            var store = new ElytraStore(database);
            UUID uuid = UUID.randomUUID();
            assertNull(store.component.loader().load(uuid).join());
            store.component.saver().save(uuid, ElytraPrefs.DEFAULT.withLaunch(false)).join();
            assertEquals(new ElytraPrefs(true, false), store.component.loader().load(uuid).join());
            store.component.saver().save(uuid, new ElytraPrefs(false, true)).join();
            assertEquals(new ElytraPrefs(false, true), store.component.loader().load(uuid).join());
        }
    }
}
