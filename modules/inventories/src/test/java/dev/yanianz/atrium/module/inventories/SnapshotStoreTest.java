package dev.yanianz.atrium.module.inventories;

import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

/** SQLite round-trip of the snapshot table (no ItemStack involved: the columns store opaque bytes). */
class SnapshotStoreTest {
    @TempDir Path directory;

    private static SnapshotStore.Draft draft(UUID target, String name, String label, SnapshotReason reason,
            long created, byte[] items) {
        return new SnapshotStore.Draft(target, name, SnapshotCodec.FORMAT, created, null, "Console", label,
                reason, 3, items);
    }

    @Test
    void storesListsReadsAndDeletesSnapshots() throws Exception {
        var config = new CoreConfig.Database();
        try (var database = new Database(config, directory, Runnable::run, LoggerFactory.getLogger(getClass()))) {
            database.migrate("inventories", SnapshotStore.MIGRATIONS);
            SnapshotStore store = new SnapshotStore(database);
            UUID target = UUID.randomUUID();
            long first = store.insert(draft(target, "Notch", "before death", SnapshotReason.MANUAL, 100,
                    new byte[] {1, 2, 3})).join();
            long second = store.insert(draft(target, "Notch", null, SnapshotReason.DEATH, 200,
                    new byte[] {4, 5})).join();
            assertNotEquals(first, second);

            SnapshotStore.Page page = store.listPage(target, 1, 10).join();
            assertEquals(2, page.total());
            assertEquals(1, page.pages());
            assertEquals(2, page.entries().size());
            assertEquals(second, page.entries().get(0).id());
            assertEquals(SnapshotReason.DEATH, page.entries().get(0).reason());
            assertEquals("Notch", page.entries().get(0).playerName());
            assertEquals(3, page.entries().get(1).stacks());

            SnapshotStore.Stored stored = store.get(target, first).join().orElseThrow();
            assertArrayEquals(new byte[] {1, 2, 3}, stored.items());
            assertEquals("before death", stored.meta().label());
            assertTrue(store.get(UUID.randomUUID(), first).join().isEmpty());

            assertEquals(second, store.latest(target).join().orElseThrow().meta().id());

            assertTrue(store.delete(target, first).join());
            assertFalse(store.delete(target, first).join());
            assertEquals(1, store.listPage(target, 1, 10).join().total());

            assertEquals(1, store.deleteAll(target).join());
            assertEquals(0, store.listPage(target, 1, 10).join().total());
            assertEquals(1, store.listPage(target, 7, 10).join().page());
        }
    }

    @Test
    void retentionAppliesCountAndAgeOnEveryInsert() throws Exception {
        var config = new CoreConfig.Database();
        try (var database = new Database(config, directory, Runnable::run, LoggerFactory.getLogger(getClass()))) {
            database.migrate("inventories", SnapshotStore.MIGRATIONS);
            SnapshotStore store = new SnapshotStore(database);
            UUID target = UUID.randomUUID();
            for (int index = 0; index < 4; index++) {
                store.insert(draft(target, "Notch", "#" + index, SnapshotReason.MANUAL, 100 + index,
                        new byte[] {(byte) index})).join();
            }
            assertEquals(2, store.prune(target, 2, 0).join());
            assertEquals(2, store.listPage(target, 1, 10).join().total());

            // Everything older than the cutoff goes, including entries that would still fit the count.
            assertEquals(2, store.prune(target, 10, 104).join());
            assertEquals(0, store.listPage(target, 1, 10).join().total());

            store.insert(draft(target, "Notch", "old", SnapshotReason.QUIT, 10, new byte[] {9})).join();
            store.insert(draft(target, "Notch", "new", SnapshotReason.QUIT, 2_000, new byte[] {9})).join();
            assertEquals(1, store.purgeOlderThan(1_000).join());
            assertEquals(1, store.listPage(target, 1, 10).join().total());
        }
    }

    @Test
    void latestCanSelectTheQuitSnapshotThatRepresentsAnOfflinePlayer() throws Exception {
        var config = new CoreConfig.Database();
        try (var database = new Database(config, directory, Runnable::run, LoggerFactory.getLogger(getClass()))) {
            database.migrate("inventories", SnapshotStore.MIGRATIONS);
            SnapshotStore store = new SnapshotStore(database);
            UUID target = UUID.randomUUID();
            long quit = store.insert(draft(target, "Notch", null, SnapshotReason.QUIT, 100,
                    new byte[] {1})).join();
            long manual = store.insert(draft(target, "Notch", null, SnapshotReason.MANUAL, 200,
                    new byte[] {2})).join();
            long newerQuit = store.insert(draft(target, "Notch", null, SnapshotReason.QUIT, 300,
                    new byte[] {3})).join();

            assertNotEquals(quit, newerQuit);
            assertEquals(newerQuit, store.latest(target, SnapshotReason.QUIT).join().orElseThrow().meta().id());
            assertEquals(newerQuit, store.latest(target, null).join().orElseThrow().meta().id());
            assertEquals(manual, store.latest(target, SnapshotReason.MANUAL).join().orElseThrow().meta().id());
            assertTrue(store.latest(target, SnapshotReason.CLEAR_ALL).join().isEmpty());
            assertTrue(store.latest(UUID.randomUUID(), SnapshotReason.QUIT).join().isEmpty());
        }
    }

    @Test
    void migrationsAreIdempotent() throws Exception {
        var config = new CoreConfig.Database();
        try (var database = new Database(config, directory, Runnable::run, LoggerFactory.getLogger(getClass()))) {
            database.migrate("inventories", SnapshotStore.MIGRATIONS);
            database.migrate("inventories", SnapshotStore.MIGRATIONS);
            SnapshotStore store = new SnapshotStore(database);
            assertEquals(0, store.listPage(UUID.randomUUID(), 1, 10).join().total());
        }
    }
}
