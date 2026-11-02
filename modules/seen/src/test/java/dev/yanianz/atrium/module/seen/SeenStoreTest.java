package dev.yanianz.atrium.module.seen;

import static org.junit.jupiter.api.Assertions.*;

import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class SeenStoreTest {
    @TempDir Path directory;

    @Test void roundTripsTheLogoutLocation() throws Exception {
        try (var database = new Database(new CoreConfig.Database(), directory, Runnable::run,
                LoggerFactory.getLogger(getClass()))) {
            database.migrate("seen", SeenStore.MIGRATIONS);
            database.migrate("seen", SeenStore.MIGRATIONS);
            var store = new SeenStore(database);
            UUID uuid = UUID.randomUUID();
            assertNull(store.component.loader().load(uuid).join());
            assertFalse(store.component.defaults().get().isKnown());
            var first = new LogoutLocation("world", 1.5, 64, -20.25, 90f, -10f, 1_000L);
            store.component.saver().save(uuid, first).join();
            assertEquals(first, store.component.loader().load(uuid).join());
            var second = new LogoutLocation("world_nether", -8, 32, 8, 0f, 0f, 2_000L);
            store.component.saver().save(uuid, second).join();
            assertEquals(second, store.component.loader().load(uuid).join());
            // An unknown location is never written over a known one.
            store.component.saver().save(uuid, LogoutLocation.NONE).join();
            assertEquals(second, store.component.loader().load(uuid).join());
        }
    }

    @Test void nullWorldMeansUnknown() {
        assertFalse(new LogoutLocation(null, 0, 0, 0, 0, 0, 0).isKnown());
    }
}
