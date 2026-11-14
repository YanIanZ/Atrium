package dev.yanianz.atrium.module.staff;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Dialect;
import dev.yanianz.atrium.core.storage.Migration;
import dev.yanianz.atrium.module.staff.SnapshotLifecycle.State;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class StaffStoreTest {
    @TempDir Path directory;

    private Database open() {
        return new Database(new CoreConfig.Database(), directory, Runnable::run, LoggerFactory.getLogger(getClass()));
    }

    @Test
    void oldRestoreCannotTransitionOrDeleteANewerSnapshot() throws Exception {
        try (var database = open()) {
            database.migrate("staff", StaffStore.MIGRATIONS);
            var store = new StaffStore(database, LoggerFactory.getLogger(getClass()), () -> "island");
            UUID uuid = UUID.randomUUID();
            assertTrue(store.begin(uuid, snapshot(1)).join());
            StaffStore.Stored first = store.load(uuid).join();
            assertEquals("island", first.origin());
            assertTrue(store.transition(uuid, first, State.ACTIVE, State.RESTORING).join());
            assertTrue(store.finish(uuid, first).join());
            assertTrue(store.begin(uuid, snapshot(2)).join());
            StaffStore.Stored second = store.load(uuid).join();
            assertFalse(first.token().equals(second.token()));
            assertFalse(store.transition(uuid, first, State.ACTIVE, State.RESTORING).join());
            assertTrue(store.transition(uuid, second, State.ACTIVE, State.RESTORING).join());
            assertFalse(store.finish(uuid, first).join());
            assertTrue(store.load(uuid).join().snapshot().sameItems(snapshot(2)));
            long archived = database.query(c -> {
                try (var ps = c.prepareStatement("SELECT COUNT(*) FROM " + database.table("staff_snapshot_history"));
                        var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }).join();
            assertEquals(1L, archived);
        }
    }

    private static StaffSnapshot snapshot(int storageByte) {
        return new StaffSnapshot(StaffSnapshot.CURRENT_FORMAT, "world", 1, 2, 3, 4, 5, "SURVIVAL", 7, 0.5f, 42,
                true, false, new byte[] {(byte) storageByte}, new byte[] {2}, new byte[] {3}, new byte[] {4},
                Boolean.TRUE, 36, 4, 1);
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
            assertTrue(store.transition(uuid, store.load(uuid).join(), State.ACTIVE, State.RESTORING).join());
            assertFalse(store.transition(uuid, store.load(uuid).join(), State.ACTIVE, State.RESTORING).join());

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
            assertTrue(store.finish(uuid, left).join());
            assertNull(store.load(uuid).join());
            assertFalse(store.finish(uuid, left).join());
        }
    }

    @Test
    void finishDoesNotDeleteAnActiveRow() throws Exception {
        try (var database = open()) {
            database.migrate("staff", StaffStore.MIGRATIONS);
            var store = new StaffStore(database, LoggerFactory.getLogger(getClass()));
            UUID uuid = UUID.randomUUID();
            assertTrue(store.begin(uuid, snapshot(1)).join());
            assertTrue(store.transition(uuid, store.load(uuid).join(), State.ACTIVE, State.RESTORING).join());
            // The inventory task never ran (player left): revert so the next join restores it again.
            assertTrue(store.transition(uuid, store.load(uuid).join(), State.RESTORING, State.ACTIVE).join());
            assertFalse(store.finish(uuid, store.load(uuid).join()).join());
            assertEquals(State.ACTIVE, store.load(uuid).join().state());
            // Nothing was archived: the archive step is skipped, not committed, when the row does not match.
            assertEquals(0L, historyCount(database));
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

    @Test
    void releasedMigrationsStayByteIdenticalAndLaterOnesAreOneDdlEach() {
        List<Migration> migrations = StaffStore.MIGRATIONS;
        assertEquals(7, migrations.size());
        for (int i = 0; i < migrations.size(); i++) {
            assertEquals(i + 1, migrations.get(i).version(), "migration versions are contiguous");
        }

        // Released migrations 1-4 are a published schema contract and must never change.
        assertStatements(1, Dialect.SQLITE,
                "CREATE TABLE {prefix}staff_snapshots (uuid CHAR(36) NOT NULL PRIMARY KEY, format INT NOT NULL,"
                        + " data BLOB NOT NULL, created BIGINT NOT NULL)");
        assertStatements(1, Dialect.MYSQL,
                "CREATE TABLE {prefix}staff_snapshots (uuid CHAR(36) NOT NULL PRIMARY KEY, format INT NOT NULL,"
                        + " data LONGBLOB NOT NULL, created BIGINT NOT NULL)");
        assertStatements(2, Dialect.SQLITE,
                "CREATE TABLE {prefix}staff_preferences (uuid CHAR(36) NOT NULL PRIMARY KEY,"
                        + " command_spy BOOLEAN NOT NULL DEFAULT FALSE)");
        assertStatements(3, Dialect.MYSQL,
                "CREATE TABLE {prefix}staff_spectate (uuid CHAR(36) NOT NULL PRIMARY KEY,"
                        + " active BOOLEAN NOT NULL, world VARCHAR(255) NOT NULL, x DOUBLE NOT NULL,"
                        + " y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL,"
                        + " game_mode VARCHAR(16) NOT NULL, target VARCHAR(16) NOT NULL)");
        assertStatements(4, Dialect.SQLITE,
                "ALTER TABLE {prefix}staff_snapshots ADD COLUMN state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'");

        // Unpublished 5-7: exactly one DDL per version, so a MySQL/MariaDB implicit commit can strand at most
        // that single statement instead of leaving earlier statements of the same version applied but unversioned.
        for (Migration migration : migrations.subList(4, migrations.size())) {
            for (Dialect dialect : Dialect.values()) {
                assertEquals(1, migration.statements().apply(dialect).size(),
                        "v" + migration.version() + " must be one statement on " + dialect);
            }
        }
        // MySQL 8 rejects ADD COLUMN IF NOT EXISTS and MARIADB shares Dialect.MYSQL, so it must not be assumed.
        for (Migration migration : migrations.subList(4, 6)) {
            for (Dialect dialect : Dialect.values()) {
                for (String statement : migration.statements().apply(dialect)) {
                    assertFalse(statement.toUpperCase(Locale.ROOT).contains("IF NOT EXISTS"),
                            "no dialect-neutral IF NOT EXISTS: " + statement);
                }
            }
        }
        // The archive table keeps the per-dialect blob type of the released snapshot table.
        assertTrue(migrations.get(6).statements().apply(Dialect.MYSQL).get(0).contains("data LONGBLOB"));
        assertTrue(migrations.get(6).statements().apply(Dialect.SQLITE).get(0).contains("data BLOB"));
    }

    @Test
    void migrationAddsIdentityColumnsWithoutRewritingExistingRows() throws Exception {
        try (var database = open()) {
            database.migrate("staff", StaffStore.MIGRATIONS.subList(0, 4));
            UUID uuid = UUID.randomUUID();
            database.update("INSERT INTO {prefix}staff_snapshots (uuid, format, data, created, state)"
                    + " VALUES (?, ?, ?, ?, ?)", uuid.toString(), 2, StaffSnapshotCodec.encode(snapshot(5)), 1L, "ACTIVE")
                    .join();
            database.migrate("staff", StaffStore.MIGRATIONS.subList(0, 5));
            database.migrate("staff", StaffStore.MIGRATIONS.subList(0, 6));
            database.migrate("staff", StaffStore.MIGRATIONS);
            database.migrate("staff", StaffStore.MIGRATIONS); // idempotent at v7

            var store = new StaffStore(database, LoggerFactory.getLogger(getClass()), () -> "island");
            StaffStore.Stored legacy = store.load(uuid).join();
            assertNotNull(legacy);
            // The defaults fill the new columns; the row itself is not rewritten and no origin is guessed.
            assertEquals("", legacy.token());
            assertEquals("", legacy.origin());
            assertEquals(1L, legacy.created());
            assertEquals(State.ACTIVE, legacy.state());
            assertTrue(legacy.snapshot().sameItems(snapshot(5)));
        }
    }

    @Test
    void adoptLegacyIsExplicitAtomicAndOnlyFillsIdentity() throws Exception {
        try (var database = open()) {
            database.migrate("staff", StaffStore.MIGRATIONS.subList(0, 4));
            UUID blank = UUID.randomUUID();
            UUID withToken = UUID.randomUUID();
            database.update("INSERT INTO {prefix}staff_snapshots (uuid, format, data, created, state)"
                    + " VALUES (?, ?, ?, ?, ?)", blank.toString(), 2, StaffSnapshotCodec.encode(snapshot(5)), 11L,
                    "ACTIVE").join();
            database.migrate("staff", StaffStore.MIGRATIONS);
            database.update("INSERT INTO {prefix}staff_snapshots"
                    + " (uuid, format, data, created, state, snapshot_id, origin_server) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    withToken.toString(), 2, StaffSnapshotCodec.encode(snapshot(6)), 12L, "ACTIVE",
                    "kept-token", "").join();

            var store = new StaffStore(database, LoggerFactory.getLogger(getClass()), () -> "island");
            StaffStore.Stored legacy = store.load(blank).join();
            assertEquals("", legacy.token());
            assertEquals("", legacy.origin());
            // Loading alone never adopts: migration cannot infer an origin on a shared network.
            assertEquals("", store.load(blank).join().origin());

            assertThrows(IllegalArgumentException.class, () -> store.adoptLegacy(blank, legacy, "  "));
            assertEquals("", store.load(blank).join().origin());

            assertTrue(store.adoptLegacy(blank, legacy, "island").join());
            StaffStore.Stored adopted = store.load(blank).join();
            // A blank legacy token gets a fresh one; payload, format, created and state are untouched.
            assertFalse(adopted.token().isBlank());
            assertEquals("island", adopted.origin());
            assertEquals(11L, adopted.created());
            assertEquals(State.ACTIVE, adopted.state());
            assertTrue(adopted.snapshot().sameItems(snapshot(5)));

            // A legacy row that already has a token keeps it; only the blank origin is filled.
            StaffStore.Stored kept = store.load(withToken).join();
            assertEquals("kept-token", kept.token());
            assertTrue(store.adoptLegacy(withToken, kept, "island").join());
            assertEquals("kept-token", store.load(withToken).join().token());
            assertEquals("island", store.load(withToken).join().origin());

            // Not atomic-safe cases: already adopted, stale token/created, or a row that no longer exists.
            assertFalse(store.adoptLegacy(blank, legacy, "island").join());
            assertFalse(store.adoptLegacy(blank, adopted, "island").join());
            assertFalse(store.adoptLegacy(blank, new StaffStore.Stored(State.ACTIVE, legacy.snapshot(), 999L, "", ""),
                    "island").join());
            assertFalse(store.adoptLegacy(UUID.randomUUID(), legacy, "island").join());
            assertEquals("island", store.load(blank).join().origin());
            assertEquals(adopted.token(), store.load(blank).join().token());
        }
    }

    @Test
    void finishArchivesTheExactRowAndAStaleFinishTouchesNothing() throws Exception {
        try (var database = open()) {
            database.migrate("staff", StaffStore.MIGRATIONS);
            var store = new StaffStore(database, LoggerFactory.getLogger(getClass()), () -> "island");
            UUID uuid = UUID.randomUUID();
            assertTrue(store.begin(uuid, snapshot(1)).join());
            StaffStore.Stored first = store.load(uuid).join();
            assertTrue(store.transition(uuid, first, State.ACTIVE, State.RESTORING).join());
            long beforeFinish = System.currentTimeMillis();
            assertTrue(store.finish(uuid, first).join());
            assertNull(store.load(uuid).join());

            // The archive is the exact row: same identity, origin, format, created stamp and payload bytes.
            Archived archived = onlyArchive(database);
            assertEquals(uuid.toString(), archived.uuid());
            assertEquals(first.token(), archived.snapshotId());
            assertEquals("island", archived.origin());
            assertEquals(snapshot(1).format(), archived.format());
            assertEquals(first.created(), archived.created());
            assertArrayEquals(StaffSnapshotCodec.encode(snapshot(1)), archived.data());
            assertTrue(archived.archived() >= beforeFinish);

            // A newer snapshot, then a stale finish (older token/created) for the same player.
            assertTrue(store.begin(uuid, snapshot(2)).join());
            StaffStore.Stored second = store.load(uuid).join();
            assertFalse(first.token().equals(second.token()));
            assertTrue(store.transition(uuid, second, State.ACTIVE, State.RESTORING).join());
            long archivesBefore = historyCount(database);
            assertFalse(store.finish(uuid, first).join());
            // Neither the archive nor the delete ran for the newer row.
            assertEquals(archivesBefore, historyCount(database));
            StaffStore.Stored survivor = store.load(uuid).join();
            assertNotNull(survivor);
            assertEquals(second.token(), survivor.token());
            assertEquals(second.created(), survivor.created());
            assertEquals(State.RESTORING, survivor.state());
            assertTrue(survivor.snapshot().sameItems(snapshot(2)));

            // The current row can still be finished and archived once.
            assertTrue(store.finish(uuid, survivor).join());
            assertEquals(archivesBefore + 1, historyCount(database));
        }
    }

    private static void assertStatements(int version, Dialect dialect, String... expected) {
        Migration migration = StaffStore.MIGRATIONS.get(version - 1);
        assertEquals(version, migration.version());
        assertEquals(List.of(expected), migration.statements().apply(dialect));
    }

    private static long historyCount(Database database) {
        return database.query(c -> {
            try (var ps = c.prepareStatement("SELECT COUNT(*) FROM " + database.table("staff_snapshot_history"));
                    var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }).join();
    }

    private static Archived onlyArchive(Database database) {
        return database.query(c -> {
            try (var ps = c.prepareStatement("SELECT archive_id, uuid, snapshot_id, origin_server, format, data,"
                    + " created, archived FROM " + database.table("staff_snapshot_history"));
                    var rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected exactly one archived row");
                Archived row = new Archived(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5), rs.getBytes(6), rs.getLong(7), rs.getLong(8));
                assertFalse(rs.next(), "expected exactly one archived row");
                return row;
            }
        }).join();
    }

    private record Archived(String archiveId, String uuid, String snapshotId, String origin, int format, byte[] data,
            long created, long archived) {
    }
}
