package dev.yanianz.atrium.module.messaging;

import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

class AlertStoreTest {
    @TempDir Path directory;

    private Database open() {
        return new Database(new CoreConfig.Database(), directory, Runnable::run,
                LoggerFactory.getLogger(getClass()));
    }

    private static AlertStore.Alert draft(UUID player, String name, String reason, long created, long expires) {
        return new AlertStore.Alert(0, player, name, reason, created, "Staff", expires);
    }

    @Test void storesPagesAndPurgesExpiredRows() throws Exception {
        try (Database db = open()) {
            db.migrate("messaging", AlertStore.MIGRATIONS);
            db.migrate("messaging", AlertStore.MIGRATIONS);
            AlertStore store = new AlertStore(db);
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            long now = System.currentTimeMillis();

            long first = store.add(draft(alice, "Alice", "first", now - 3_000, 0)).join();
            long second = store.add(draft(alice, "Alice", "second", now - 2_000, 0)).join();
            long third = store.add(draft(bob, "Bob", "third", now - 1_000, 0)).join();
            assertTrue(first > 0);
            assertTrue(third > second);

            AlertStore.Page page = store.page(1, 2, now).join();
            assertEquals(1, page.page());
            assertEquals(2, page.pages());
            assertEquals(3, page.total());
            assertEquals(2, page.entries().size());
            assertEquals("third", page.entries().getFirst().reason());
            assertEquals("second", page.entries().get(1).reason());

            AlertStore.Page last = store.page(99, 2, now).join();
            assertEquals(2, last.page());
            assertEquals("first", last.entries().getFirst().reason());

            List<AlertStore.Alert> forAlice = store.forPlayer(alice, now).join();
            assertEquals(2, forAlice.size());
            assertEquals("first", forAlice.getFirst().reason());
            assertEquals("second", forAlice.get(1).reason());

            assertEquals(2, store.removeAll(alice).join());
            assertEquals(0, store.removeAll(alice).join());
            assertEquals(1, store.page(1, 10, now).join().total());
        }
    }

    @Test void filtersAndPurgesByExpiry() throws Exception {
        try (Database db = open()) {
            db.migrate("messaging", AlertStore.MIGRATIONS);
            AlertStore store = new AlertStore(db);
            UUID player = UUID.randomUUID();
            long now = System.currentTimeMillis();

            store.add(draft(player, "Player", "active", now, now + 60_000)).join();
            store.add(draft(player, "Player", "later", now, now + 120_000)).join();

            assertEquals(2, store.forPlayer(player, now).join().size());
            assertEquals(1, store.forPlayer(player, now + 90_000).join().size());
            assertEquals(1, store.page(1, 10, now + 90_000).join().total());

            assertEquals(2, store.purgeExpired(now + 200_000).join());
            assertEquals(0, store.page(1, 10, now).join().total());
        }
    }

    @Test void emptyStoreHasAnEmptyFirstPage() throws Exception {
        try (Database db = open()) {
            db.migrate("messaging", AlertStore.MIGRATIONS);
            AlertStore store = new AlertStore(db);
            AlertStore.Page page = store.page(5, 10, System.currentTimeMillis()).join();
            assertEquals(1, page.page());
            assertEquals(0, page.pages());
            assertEquals(0, page.total());
            assertTrue(page.entries().isEmpty());
        }
    }
}
