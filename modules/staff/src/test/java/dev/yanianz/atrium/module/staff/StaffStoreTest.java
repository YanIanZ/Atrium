package dev.yanianz.atrium.module.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.module.staff.SnapshotLifecycle.State;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class StaffStoreTest {
    @TempDir Path directory;

    private Database open() {
        return new Database(new CoreConfig.Database(), directory, Runnable::run, LoggerFactory.getLogger(getClass()));
    }

    private static StaffSnapshot snapshot(int storageByte) {
        return new StaffSnapshot(StaffSnapshot.CURRENT_FORMAT, "world", 1, 2, 3, 4, 5, "SURVIVAL", 7, 0.5f, 42,
                true, false, new byte[] {(byte) storageByte}, new byte[] {2}, new byte[] {3}, new byte[] {4},
                Boolean.TRUE);
    }

    @Test
    void snapshotsAndPreferencesRoundTrip() throws Exception {
        try (var database = open()) {
            database.migrate("staff", StaffStore.MIGRATIONS);
            database.migrate("staff", StaffStore.MIGRATIONS); // idempotent
            var store = new StaffStore(database, LoggerFactory.getLogger(getClass()));
            UUID uuid = UUID.randomUUID();

            assertNull(store.load(uuid).join());
            assertEquals(StaffPreferences.DEFAULT, store.preferences.defaults().get());
            assertEquals(SpectateState.EMPTY, store.spectate.defaults().get());

            assertTrue(store.begin(uuid, snapshot(1)).join());
            StaffStore.Stored loaded = store.load(uuid).join();
            assertNotNull(loaded);
            assertEquals(State.ACTIVE, loaded.state());
            assertEquals(42, loaded.snapshot().totalExperience());
            assertEquals(Boolean.TRUE, loaded.snapshot().vanished());
            assertTrue(loaded.snapshot().sameItems(snapshot(1)));

            store.preferences.saver().save(uuid, new StaffPreferences(true)).join();
            assertEquals(new StaffPreferences(true), store.preferences.loader().load(uuid).join());

            SpectateState spectate = SpectateState.started("world", 8, 9, 10, 11, 12, "ADVENTURE", "Notch");
            store.spectate.saver().save(uuid, spectate).join();
            assertEquals(spectate, store.spectate.loader().load(uuid).join());
        }
    }

    @Test
    void restoreIsTwoPhaseAndNeverRestartsFromRestoring() throws Exception {
        try (var database = open()) {
            database.migrate("staff", StaffStore.MIGRATIONS);
            var store = new StaffStore(database, LoggerFactory.getLogger(getClass()));
            UUID uuid = UUID.randomUUID();
            assertTrue(store.begin(uuid, snapshot(1)).join());

            // Phase 1: ACTIVE -> RESTORING is a compare-and-set; a second exit cannot start another restore.
            assertTrue(store.transition(uuid, State.ACTIVE, State.RESTORING).join());
            assertFalse(store.transition(uuid, State.ACTIVE, State.RESTORING).join());

            // Delete "failed": the row stays RESTORING. The next join must not see an ACTIVE row.
            StaffStore.Stored left = store.load(uuid).join();
            assertEquals(State.RESTORING, left.state());
            assertEquals(SnapshotLifecycle.JoinAction.HOLD_FOR_REVIEW,
                    SnapshotLifecycle.onJoin(left.state(), left.corrupt(), false));
            assertEquals(List.of(uuid), store.withState(State.RESTORING).join());

            // A held row is never overwritten by a new staff session.
            assertFalse(store.begin(uuid, snapshot(9)).join());
            assertTrue(store.load(uuid).join().snapshot().sameItems(snapshot(1)));

            // Phase 2: finishing deletes only a RESTORING row.
            assertTrue(store.finish(uuid).join());
            assertNull(store.load(uuid).join());
            assertFalse(store.finish(uuid).join());
        }
    }

    @Test
    void finishDoesNotDeleteAnActiveRow() throws Exception {
        try (var database = open()) {
            database.migrate("staff", StaffStore.MIGRATIONS);
            var store = new StaffStore(database, LoggerFactory.getLogger(getClass()));
            UUID uuid = UUID.randomUUID();
            assertTrue(store.begin(uuid, snapshot(1)).join());
            assertTrue(store.transition(uuid, State.ACTIVE, State.RESTORING).join());
            // The inventory task never ran (player left): revert so the next join restores it again.
            assertTrue(store.transition(uuid, State.RESTORING, State.ACTIVE).join());
            assertFalse(store.finish(uuid).join());
            assertEquals(State.ACTIVE, store.load(uuid).join().state());
        }
    }

    @Test
    void rowsFromBeforeTheStateColumnAreActive() throws Exception {
        try (var database = open()) {
            database.migrate("staff", StaffStore.MIGRATIONS.subList(0, 3));
            UUID uuid = UUID.randomUUID();
            database.update("INSERT INTO {prefix}staff_snapshots (uuid, format, data, created) VALUES (?, ?, ?, ?)",
                    uuid.toString(), 2, StaffSnapshotCodec.encode(snapshot(5)), 1L).join();
            database.migrate("staff", StaffStore.MIGRATIONS);
            var store = new StaffStore(database, LoggerFactory.getLogger(getClass()));
            assertEquals(State.ACTIVE, store.load(uuid).join().state());
        }
    }

    @Test
    void corruptRowsAreReportedNotHidden() throws Exception {
        try (var database = open()) {
            database.migrate("staff", StaffStore.MIGRATIONS);
            UUID uuid = UUID.randomUUID();
            database.update("INSERT INTO {prefix}staff_snapshots (uuid, format, data, created, state)"
                    + " VALUES (?, ?, ?, ?, ?)", uuid.toString(), 2, new byte[] {1, 2, 3}, 1L, "ACTIVE").join();
            var store = new StaffStore(database, LoggerFactory.getLogger(getClass()));
            StaffStore.Stored stored = store.load(uuid).join();
            assertNotNull(stored);
            assertTrue(stored.corrupt());
            assertFalse(store.begin(uuid, snapshot(1)).join(), "a corrupt row must not be overwritten silently");
        }
    }
}
