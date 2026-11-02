package dev.yanianz.atrium.module.vanish;

import static org.junit.jupiter.api.Assertions.*;

import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class VanishStoreTest {
    @TempDir Path directory;

    @Test void roundTripsAndUpdatesState() throws Exception {
        try (var database = new Database(new CoreConfig.Database(), directory, Runnable::run,
                LoggerFactory.getLogger(getClass()))) {
            database.migrate("vanish", VanishStore.MIGRATIONS);
            database.migrate("vanish", VanishStore.MIGRATIONS); // idempotent
            var store = new VanishStore(database);
            UUID uuid = UUID.randomUUID();
            assertNull(store.load(uuid).join(), "nothing stored yet");
            assertEquals(VanishState.DEFAULT, store.component.defaults().get());
            store.component.saver().save(uuid, new VanishState(true, false, false)).join();
            assertEquals(new VanishState(true, false, false), store.component.loader().load(uuid).join());
            store.component.saver().save(uuid, new VanishState(false, true, true)).join();
            assertEquals(new VanishState(false, true, true), store.load(uuid).join());
        }
    }

    @Test void togglesPreserveTheOtherFlags() {
        assertEquals(new VanishState(true, true, false), new VanishState(false, true, false).withVanished(true));
        assertEquals(new VanishState(true, false, false), new VanishState(true, true, false).withPickup(false));
        assertEquals(new VanishState(true, true, true),
                new VanishState(true, true, false).withSilentChest(true));
    }
}
