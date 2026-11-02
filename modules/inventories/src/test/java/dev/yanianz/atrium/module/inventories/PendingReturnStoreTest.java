package dev.yanianz.atrium.module.inventories;

import static org.junit.jupiter.api.Assertions.*;

import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** SQLite round-trip of the queued returns table; the payload bytes stay opaque here. */
class PendingReturnStoreTest {
    @TempDir Path directory;

    private static final List<Migration> MIGRATIONS = Stream.concat(PendingEditStore.MIGRATIONS.stream(),
            PendingReturnStore.MIGRATIONS.stream()).toList();

    private static PendingReturnStore.Draft draft(UUID admin, String name, long created, byte[] items) {
        return new PendingReturnStore.Draft(admin, name, created, "contents-changed", "Notch", 2, items);
    }

    @Test
    void queuesPerAdministratorAndDeletesOnlyAfterDelivery() throws Exception {
        var config = new CoreConfig.Database();
        try (var database = new Database(config, directory, Runnable::run, LoggerFactory.getLogger(getClass()))) {
            database.migrate("inventories", MIGRATIONS);
            PendingReturnStore store = new PendingReturnStore(database);
            UUID admin = UUID.randomUUID();
            UUID other = UUID.randomUUID();

            long first = store.insert(draft(admin, "Admin", 100, new byte[] {1})).join();
            store.insert(draft(other, "Other", 100, new byte[] {2})).join();
            store.insert(draft(admin, "Admin", 200, new byte[] {3})).join();

            List<PendingReturnStore.Row> rows = store.pending(admin).join();
            assertEquals(2, rows.size());
            assertEquals(first, rows.get(0).id());
            assertEquals(2, rows.get(0).stacks());
            assertEquals("Notch", rows.get(0).detail());
            assertArrayEquals(new byte[] {1}, rows.get(0).items());
            assertEquals(1, store.pending(other).join().size());

            assertTrue(store.delete(first).join());
            assertFalse(store.delete(first).join());
            assertEquals(1, store.pending(admin).join().size());
        }
    }

    @Test
    void reasonIsBoundedToTheColumn() {
        assertEquals("rejected-edit", PendingReturnStore.reason(null));
        assertEquals("rejected-edit", PendingReturnStore.reason("  "));
        assertEquals("Contents-Changed", PendingReturnStore.reason(" Contents-Changed "));
        assertEquals(32, PendingReturnStore.reason("x".repeat(60)).length());
    }
}
