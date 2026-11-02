package dev.yanianz.atrium.module.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.moderation.Punishment;
import dev.yanianz.atrium.api.moderation.PunishmentType;
import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** Table-backed pieces added with jail cells, sentence pauses, chat mute state, notes and ban edits. */
class ModerationStoreTest {
    @TempDir Path folder;
    private Database db;
    private ExecutorService executor;
    private JailStore jails;

    @BeforeEach
    void setup() throws Exception {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        db = new Database(new CoreConfig.Database(), folder, executor, LoggerFactory.getLogger("moderation-test"));
        db.migrate("moderation", PunishmentStore.MIGRATIONS);
        JailStore.ensureEditorColumns(db).join();
        jails = new JailStore(db);
        jails.load().join();
    }

    @AfterEach
    void close() {
        db.close();
        executor.close();
    }

    private static Punishment jail(UUID target, Instant now, Duration duration) {
        return new Punishment(0, PunishmentType.JAIL, target, "Steve", null, null, "Console", "r", now,
                duration == null ? null : now.plus(duration), null, null, null, "cell");
    }

    private static Punishment ban(UUID target, Instant now, Duration duration) {
        return new Punishment(0, PunishmentType.TEMPBAN, target, "Steve", null, null, "Console", "r", now,
                now.plus(duration), null, null, null, null);
    }

    @Test
    void cellsAreDenseAndPersisted() {
        JailStore store = new JailStore(db);
        store.load().join();
        store.save(new Jail("cell", "world", 0, 64, 0, 0, 0, 8, null, List.of(), null, true)).join();

        store.addCell("cell", new JailCells.Cell("world", 1, 64, 1, 0, 0)).join();
        store.addCell("cell", new JailCells.Cell("world", 2, 64, 2, 0, 0)).join();
        store.addCell("cell", new JailCells.Cell("world", 3, 64, 3, 0, 0)).join();
        assertEquals(3, store.cellCount("cell"));

        assertTrue(store.removeCell("cell", 1).join());
        assertFalse(store.removeCell("cell", 5).join());
        assertEquals(List.of(
                new JailCells.Cell("world", 1, 64, 1, 0, 0),
                new JailCells.Cell("world", 3, 64, 3, 0, 0)), store.cells("cell"));

        JailStore reloaded = new JailStore(db);
        reloaded.load().join();
        assertEquals(store.cells("cell"), reloaded.cells("cell"), "cells survive a restart in order");

        reloaded.clearCells("cell").join();
        assertTrue(reloaded.cells("cell").isEmpty());
    }

    @Test
    void deletingAJailDeletesItsCells() {
        JailStore store = new JailStore(db);
        store.load().join();
        store.save(new Jail("cell", "world", 0, 64, 0, 0, 0, 8, null, List.of(), null, true)).join();
        store.addCell("cell", new JailCells.Cell("world", 1, 64, 1, 0, 0)).join();

        store.delete("cell").join();
        assertTrue(store.cells("cell").isEmpty());
        JailStore reloaded = new JailStore(db);
        reloaded.load().join();
        assertTrue(reloaded.cells("cell").isEmpty());
    }

    @Test
    void theOfflinePauseIsReadOnceAndDeleted() {
        PunishmentStore store = new PunishmentStore(db);
        UUID uuid = UUID.randomUUID();
        Instant at = Instant.parse("2026-01-01T10:00:00Z");
        store.pauseJail(uuid, at).join();

        assertEquals(Optional.of(at), store.takeJailPause(uuid).join());
        assertEquals(Optional.empty(), store.takeJailPause(uuid).join(), "the marker is consumed");
    }

    @Test
    void aTimedSentenceIsExtendedButAPermanentOneIsNot() {
        PunishmentStore store = new PunishmentStore(db);
        Instant now = Instant.now();
        UUID timed = UUID.randomUUID();
        UUID permanent = UUID.randomUUID();
        store.insert(jail(timed, now, Duration.ofHours(1)), Set.of()).join();
        store.insert(jail(permanent, now, null), Set.of()).join();

        assertEquals(1, store.extendJail(timed, Duration.ofMinutes(30).toMillis(), now).join());
        long expires = store.active(timed, now).join().getFirst().expires().toEpochMilli();
        assertEquals(now.plus(Duration.ofHours(1)).plus(Duration.ofMinutes(30)).toEpochMilli(), expires);
        assertEquals(0, store.extendJail(permanent, 60_000, now).join(), "a permanent sentence has no clock");
    }

    @Test
    void theChatMuteDeadlineRoundTrips() {
        PunishmentStore store = new PunishmentStore(db);
        assertTrue(store.loadState(MuteChatState.stateKey()).join().isEmpty());

        Instant until = Instant.now().plus(Duration.ofHours(1));
        MuteChatState state = new MuteChatState(until, "spam", "Alex");
        store.saveState(MuteChatState.stateKey(), state).join();
        assertEquals(Optional.of(state), store.loadState(MuteChatState.stateKey()).join());

        store.saveState(MuteChatState.stateKey(), MuteChatState.NONE).join();
        MuteChatState cleared = store.loadState(MuteChatState.stateKey()).join().orElseThrow();
        assertNull(cleared.until(), "an unmuted chat has no deadline");
    }

    @Test
    void notesCanBeAddedListedAndRemoved() {
        NoteStore notes = new NoteStore(db);
        UUID target = UUID.randomUUID();
        Instant now = Instant.now();
        long first = notes.add(target, "Steve", UUID.randomUUID(), "Alex", "first", now).join();
        long second = notes.add(target, "Steve", null, "Console", "second", now.plusSeconds(1)).join();

        assertEquals(2, notes.count(target).join());
        assertEquals(List.of("first", "second"), notes.page(target, 0, 10).join().stream()
                .map(NoteStore.Note::body).toList());
        assertTrue(notes.byId(target, first).join().isPresent());
        assertTrue(notes.byId(UUID.randomUUID(), first).join().isEmpty(), "a note belongs to one account");

        assertTrue(notes.remove(target, first).join());
        assertFalse(notes.remove(target, first).join());
        assertEquals(1, notes.clear(target).join());
        assertEquals(0, notes.count(target).join());
        assertEquals(2, second, "ids are positive");
    }

    @Test
    void anActiveBanCanBeEditedAndALiftedOneCannot() {
        PunishmentStore store = new PunishmentStore(db);
        Instant now = Instant.now();
        UUID target = UUID.randomUUID();
        Punishment stored = store.insert(ban(target, now, Duration.ofHours(1)), Set.of()).join();

        assertTrue(store.updateActiveReason(stored.id(), "edited", now).join());
        Instant newExpiry = now.plus(Duration.ofDays(2)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        assertTrue(store.updateActiveExpiry(stored.id(), newExpiry, now).join());
        Punishment edited = store.byId(stored.id()).join().orElseThrow();
        assertEquals("edited", edited.reason());
        assertEquals(newExpiry, edited.expires());

        assertTrue(store.updateActiveExpiry(stored.id(), null, now).join(), "permanent is a null expiry");
        assertNull(store.byId(stored.id()).join().orElseThrow().expires());

        store.revoke(List.of(stored.id()), null, "Console", now).join();
        assertFalse(store.updateActiveReason(stored.id(), "late", now).join(),
                "a lifted ban can no longer be edited");
    }
}
