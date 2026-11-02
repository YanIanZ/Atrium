package dev.yanianz.atrium.module.inventories;

import static org.junit.jupiter.api.Assertions.*;

import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** SQLite round-trip of the pending edit table; item bytes stay opaque here, like the snapshot store test. */
class PendingEditStoreTest {
    @TempDir Path directory;

    private static final java.util.List<dev.yanianz.atrium.core.storage.Migration> MIGRATIONS =
            java.util.stream.Stream.concat(PendingEditStore.MIGRATIONS.stream(),
                    PendingReturnStore.MIGRATIONS.stream()).toList();

    private static PendingEditStore.Draft draft(UUID target, String name, long base, long created, byte[] items) {
        return draft(target, name, base, created, items, 0, null);
    }

    private static PendingEditStore.Draft draft(UUID target, String name, long base, long created, byte[] items,
            int deltaStacks, byte[] deltaItems) {
        return new PendingEditStore.Draft(target, name, base, SnapshotCodec.FORMAT, created, UUID.randomUUID(),
                "Admin", 2, items, deltaStacks, deltaItems);
    }

    @Test
    void storesOneOpenEditPerPlayerAndResolvesItConditionally() throws Exception {
        var config = new CoreConfig.Database();
        try (var database = new Database(config, directory, Runnable::run, LoggerFactory.getLogger(getClass()))) {
            database.migrate("inventories", MIGRATIONS);
            PendingEditStore store = new PendingEditStore(database);
            UUID target = UUID.randomUUID();

            long first = store.insert(draft(target, "Notch", 10, 100, new byte[] {1})).join().id();
            PendingEditStore.Row open = store.latestUnresolved(target).join().orElseThrow();
            assertEquals(first, open.id());
            assertEquals(10L, open.baseSnapshot());
            assertArrayEquals(new byte[] {1}, open.items());
            assertEquals(PendingEditStore.Status.PENDING, open.status());

            // A newer edit supersedes the older one instead of leaving two edits to apply.
            long second = store.insert(draft(target, "Notch", 10, 200, new byte[] {2})).join().id();
            assertEquals(1, store.unresolved().join().size());
            assertEquals(second, store.latestUnresolved(target).join().orElseThrow().id());

            // The superseded row can no longer be resolved; only the open one can.
            assertFalse(store.resolve(first, PendingEditStore.Status.APPLIED, "applied").join());
            assertTrue(store.resolve(second, PendingEditStore.Status.DISCARDED, "contents-changed").join());
            assertFalse(store.resolve(second, PendingEditStore.Status.DISCARDED, "contents-changed").join());
            assertTrue(store.latestUnresolved(target).join().isEmpty());
        }
    }

    @Test
    void keepsContributedStacksAndReportsTheEditItSuperseded() throws Exception {
        var config = new CoreConfig.Database();
        try (var database = new Database(config, directory, Runnable::run, LoggerFactory.getLogger(getClass()))) {
            database.migrate("inventories", MIGRATIONS);
            PendingEditStore store = new PendingEditStore(database);
            UUID target = UUID.randomUUID();
            byte[] delta = {9, 8, 7};

            long first = store.insert(draft(target, "Notch", 1, 100, new byte[] {1}, 3, delta)).join().id();
            PendingEditStore.Row open = store.latestUnresolved(target).join().orElseThrow();
            assertEquals(3, open.deltaStacks());
            assertArrayEquals(delta, open.deltaItems());

            // The next edit closes the first and reports the stacks its editor still owns.
            PendingEditStore.Inserted inserted = store.insert(draft(target, "Notch", 2, 200, new byte[] {2},
                    0, null)).join();
            assertEquals(1, inserted.superseded().size());
            PendingEditStore.Superseded superseded = inserted.superseded().get(0);
            assertEquals(first, superseded.id());
            assertEquals(3, superseded.deltaStacks());
            assertArrayEquals(delta, superseded.deltaItems());

            // An edit without contributed stacks round-trips as empty instead of failing.
            PendingEditStore.Row second = store.latestUnresolved(target).join().orElseThrow();
            assertEquals(inserted.id(), second.id());
            assertEquals(0, second.deltaStacks());
            assertNull(second.deltaItems());
            assertTrue(store.insert(draft(target, "Notch", 3, 300, new byte[] {3})).join()
                    .superseded().get(0).deltaItems() == null);
        }
    }

    @Test
    void resolvesEveryOpenEditOfOnePlayerOnly() throws Exception {
        var config = new CoreConfig.Database();
        try (var database = new Database(config, directory, Runnable::run, LoggerFactory.getLogger(getClass()))) {
            database.migrate("inventories", MIGRATIONS);
            PendingEditStore store = new PendingEditStore(database);
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            store.insert(draft(first, "Notch", 1, 100, new byte[] {1})).join();
            store.insert(draft(second, "Herobrine", 2, 100, new byte[] {2})).join();

            assertEquals(1, store.resolveOpen(first, PendingEditStore.Status.DISCARDED, "missing-base").join());
            assertEquals(1, store.unresolved().join().size());
            assertEquals(second, store.unresolved().join().get(0).target());
        }
    }

    @Test
    void agePurgeRemovesResolvedRowsButKeepsAnOpenEdit() throws Exception {
        var config = new CoreConfig.Database();
        try (var database = new Database(config, directory, Runnable::run, LoggerFactory.getLogger(getClass()))) {
            database.migrate("inventories", MIGRATIONS);
            PendingEditStore store = new PendingEditStore(database);
            UUID target = UUID.randomUUID();
            long old = store.insert(draft(target, "Notch", 1, 100, new byte[] {1})).join().id();
            assertTrue(store.resolve(old, PendingEditStore.Status.DISCARDED, "contents-changed").join());
            store.insert(draft(target, "Notch", 2, 2_000, new byte[] {2})).join();

            // The resolved row is pruned; the open edit is kept until its player joins.
            assertEquals(1, store.purgeOlderThan(1_000).join());
            assertEquals(1, store.unresolved().join().size());
            assertEquals(2_000, store.unresolved().join().get(0).created());
        }
    }

}
