package dev.yanianz.atrium.module.afk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class AfkStoreTest {
    @TempDir Path folder;
    Database db;
    AfkStore store;
    ExecutorService executor;

    @BeforeEach
    void setup() throws Exception {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        db = new Database(new CoreConfig.Database(), folder, executor, LoggerFactory.getLogger("afk-test"));
        db.migrate("afk", AfkStore.MIGRATIONS);
        store = new AfkStore(db);
    }

    @AfterEach
    void close() {
        db.close();
        executor.close();
    }

    @Test
    void missingProfileLoadsAsNullSoDefaultsApply() {
        assertNull(store.component.loader().load(UUID.randomUUID()).join());
    }

    @Test
    void profileRoundTripsAndUpserts() {
        UUID player = UUID.randomUUID();
        store.component.saver().save(player, new AfkProfile(30, 1)).join();
        assertEquals(new AfkProfile(30, 1), store.component.loader().load(player).join());

        store.component.saver().save(player, new AfkProfile(90, 2)).join();
        assertEquals(new AfkProfile(90, 2), store.component.loader().load(player).join());
    }
}
