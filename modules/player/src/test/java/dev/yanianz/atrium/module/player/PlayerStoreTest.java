package dev.yanianz.atrium.module.player;

import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class PlayerStoreTest {
    @TempDir Path directory;

    @Test void upgradesV1WithoutLosingFlightAndRoundTripsGod() throws Exception {
        var config = new CoreConfig.Database();
        try (var database = new Database(config, directory, Runnable::run, LoggerFactory.getLogger(getClass()))) {
            database.migrate("player", List.of(PlayerStore.MIGRATIONS.getFirst()));
            UUID uuid = UUID.randomUUID();
            database.update("INSERT INTO {prefix}player_state (uuid, flying) VALUES (?, ?)", uuid.toString(), true).join();
            database.migrate("player", PlayerStore.MIGRATIONS);
            var store = new PlayerStore(database);
            assertEquals(new PlayerState(true, false), store.component.loader().load(uuid).join());
            store.component.saver().save(uuid, new PlayerState(true, true)).join();
            assertEquals(new PlayerState(true, true), store.component.loader().load(uuid).join());
            database.migrate("player", PlayerStore.MIGRATIONS);
            assertEquals(new PlayerState(true, true), store.component.loader().load(uuid).join());
            UUID fresh = UUID.randomUUID();
            store.component.saver().save(fresh, new PlayerState(false, true)).join();
            assertEquals(new PlayerState(false, true), store.component.loader().load(fresh).join());
        }
    }

    @Test void togglingFlightDoesNotClearGodAndViceVersa() {
        assertEquals(new PlayerState(false, true), new PlayerState(true, true).withFlying(false));
        assertEquals(new PlayerState(true, false), new PlayerState(true, true).withGod(false));
    }
}
