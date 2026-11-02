package dev.yanianz.atrium.module.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Dialect;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** The v4 schema step, editor options, and the serialised read-modify-write, against a real SQLite database. */
class JailStoreTest {
    @TempDir Path folder;
    private Database db;
    private ExecutorService executor;

    @BeforeEach
    void setup() throws Exception {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        db = new Database(new CoreConfig.Database(), folder, executor, LoggerFactory.getLogger("jail-test"));
        db.migrate("moderation", PunishmentStore.MIGRATIONS);
        JailStore.ensureEditorColumns(db).join();
    }

    @AfterEach
    void close() {
        db.close();
        executor.close();
    }

    private static Jail jail(String name, double radius) {
        return new Jail(name, "world", 10, 64, 10, 0, 0, radius, null, List.of(), null, true);
    }

    @Test
    void editorOptionsRoundTripAndCanBeCleared() {
        JailStore store = new JailStore(db);
        store.load().join();
        ReleaseLocation release = new ReleaseLocation("world", 1.5, 64, -2.5, 90, 0);
        Jail jail = new Jail("cell", "world", 10, 64, 10, 0, 0, 8, release,
                List.of("msg", "mail send"), Duration.ofHours(2), false);
        store.save(jail).join();

        JailStore reloaded = new JailStore(db);
        reloaded.load().join();
        Jail loaded = reloaded.get("CELL").orElseThrow();
        assertEquals("cell", loaded.name(), "names are stored lower-case");
        assertEquals("world", loaded.world());
        assertEquals(8, loaded.radius());
        assertEquals(release, loaded.release());
        assertEquals(List.of("msg", "mail send"), loaded.allowedCommands());
        assertEquals(Duration.ofHours(2), loaded.maxSentence());
        assertFalse(loaded.chatAllowed());

        reloaded.update("cell", current -> current.withRelease(null).withAllowedCommands(List.of())
                .withMaxSentence(null).withChatAllowed(true).withRadius(12)).join();
        JailStore third = new JailStore(db);
        third.load().join();
        Jail cleared = third.get("cell").orElseThrow();
        assertNull(cleared.release());
        assertTrue(cleared.allowedCommands().isEmpty());
        assertNull(cleared.maxSentence());
        assertTrue(cleared.chatAllowed());
        assertEquals(12, cleared.radius());

        assertTrue(third.delete("cell").join());
        assertTrue(third.get("cell").isEmpty());
    }

    @Test
    void activePrisonersAreCounted() {
        JailStore store = new JailStore(db);
        store.load().join();
        store.save(new Jail("cell", "world", 0, 0, 0, 0, 0, 4, null, List.of(), null, true)).join();

        PunishmentStore punishments = new PunishmentStore(db);
        java.time.Instant now = java.time.Instant.now();
        punishments.insert(new dev.yanianz.atrium.api.moderation.Punishment(0,
                dev.yanianz.atrium.api.moderation.PunishmentType.JAIL, java.util.UUID.randomUUID(), "Steve", null,
                null, "Console", "r", now, null, null, null, null, "cell"), java.util.Set.of()).join();

        assertEquals(1, punishments.countActiveJail("cell", now).join());
        assertEquals(0, punishments.countActiveJail("other", now).join());
    }

    @Test
    void theV4SchemaStepCanBeReRun() {
        // A partially applied MySQL v4 leaves columns behind without the version bump; re-running the step
        // must skip the existing columns instead of failing with "duplicate column".
        JailStore.ensureEditorColumns(db).join();
        JailStore.ensureEditorColumns(db).join();

        JailStore store = new JailStore(db);
        store.load().join();
        store.save(jail("cell", 4).withChatAllowed(false)).join();
        assertEquals(false, store.get("cell").orElseThrow().chatAllowed());
    }

    @Test
    void aPartiallyAppliedV4IsRepairedInsteadOfFailingOnADuplicateColumn() throws Exception {
        // Simulate the MySQL state a half-applied v4 leaves behind: one editor column exists, the version
        // row is still 3. The step must add only the missing columns.
        Path partial = folder.resolve("partial");
        java.nio.file.Files.createDirectories(partial);
        try (Database other = new Database(new CoreConfig.Database(), partial, executor,
                LoggerFactory.getLogger("partial-jail-test"))) {
            other.migrate("moderation", PunishmentStore.MIGRATIONS);
            other.update("ALTER TABLE {prefix}moderation_jails ADD COLUMN release_world VARCHAR(64)").join();

            JailStore.ensureEditorColumns(other).join();

            JailStore store = new JailStore(other);
            store.load().join();
            store.save(jail("cell", 4)).join();
            assertTrue(store.get("cell").orElseThrow().allowedCommands().isEmpty());
        }
    }

    @Test
    void anIncompleteStateTableFromADevelopmentBuildIsRepaired() throws Exception {
        // A development build created moderation_state without expires_at/reason ("no such column").
        Path dev = folder.resolve("dev-state");
        java.nio.file.Files.createDirectories(dev);
        try (Database other = new Database(new CoreConfig.Database(), dev, executor,
                LoggerFactory.getLogger("state-repair-test"))) {
            other.migrate("moderation", PunishmentStore.MIGRATIONS);
            other.update("DROP TABLE {prefix}moderation_state").join();
            other.update("CREATE TABLE {prefix}moderation_state (state_key VARCHAR(32) NOT NULL PRIMARY KEY, "
                    + "actor_name VARCHAR(64) NOT NULL)").join();

            JailStore.ensureStateColumns(other).join();
            JailStore.ensureStateColumns(other).join(); // idempotent

            other.update("INSERT INTO {prefix}moderation_state (state_key, expires_at, reason, actor_name) "
                    + "VALUES (?, ?, ?, ?)", "chat", 123L, "test", "Console").join();
            Long expires = other.query(c -> {
                try (var ps = c.prepareStatement(other.expand(
                        "SELECT expires_at FROM {prefix}moderation_state WHERE state_key = 'chat'"));
                        var rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : null;
                }
            }).join();
            assertEquals(123L, expires);
        }
    }

    @Test
    void mysqlV4IsOneAlterStatement() {
        List<String> missing = List.copyOf(JailStore.editorColumns(Dialect.MYSQL).keySet());
        List<String> mysql = JailStore.alterStatements(Dialect.MYSQL, "atrium_moderation_jails", missing);
        assertEquals(1, mysql.size(), "one atomic DDL statement on MySQL");
        for (String column : missing) {
            assertTrue(mysql.getFirst().contains("ADD COLUMN " + column), column);
        }

        List<String> sqlite = JailStore.alterStatements(Dialect.SQLITE, "atrium_moderation_jails", missing);
        assertEquals(missing.size(), sqlite.size(), "SQLite needs one ALTER per column");
        assertTrue(JailStore.alterStatements(Dialect.MYSQL, "t", List.of()).isEmpty());
    }

    @Test
    void concurrentEditsAddUpAndArePersisted() {
        JailStore store = new JailStore(db);
        store.load().join();
        store.save(jail("cell", 4)).join();

        List<CompletableFuture<?>> edits = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            // +1 per edit, not a fixed value: a lost update would leave the radius short.
            edits.add(store.update("cell", current -> current.withRadius(current.radius() + 1)));
        }
        CompletableFuture.allOf(edits.toArray(CompletableFuture[]::new)).join();

        assertEquals(24, store.get("cell").orElseThrow().radius(), "no edit may be lost");
        JailStore reloaded = new JailStore(db);
        reloaded.load().join();
        assertEquals(24, reloaded.get("cell").orElseThrow().radius(), "the cache must match the database");
    }

    @Test
    void aDeleteLeavesATombstoneThatAbortsLateEdits() {
        JailStore store = new JailStore(db);
        store.load().join();
        store.save(jail("cell", 4)).join();

        assertTrue(store.delete("cell").join());
        assertTrue(store.update("cell", current -> current.withRadius(9)).join().isEmpty());
        assertTrue(store.get("cell").isEmpty(), "a late edit must not resurrect the deleted jail");

        JailStore reloaded = new JailStore(db);
        reloaded.load().join();
        assertTrue(reloaded.get("cell").isEmpty());
    }

    @Test
    void editsOnDifferentJailsDoNotBlockEachOther() {
        JailStore store = new JailStore(db);
        store.load().join();
        store.save(jail("one", 1)).join();
        store.save(jail("two", 1)).join();

        IntStream.range(0, 10).forEach(i -> {
            store.update("one", current -> current.withRadius(current.radius() + 1)).join();
            store.update("two", current -> current.withRadius(current.radius() + 2)).join();
        });
        assertEquals(11, store.get("one").orElseThrow().radius());
        assertEquals(21, store.get("two").orElseThrow().radius());
    }
}
