package dev.yanianz.atrium.module.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportCause;
import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** Storage round trips: cooldowns and /back history survive a restart (a new store on the same database). */
class CooldownPersistenceTest {
    @TempDir
    Path dir;

    private Database open() throws Exception {
        Database db = new Database(new CoreConfig.Database(), dir, Executors.newVirtualThreadPerTaskExecutor(),
                LoggerFactory.getLogger("test"));
        db.migrate("teleport", TeleportStore.MIGRATIONS);
        return db;
    }

    @Test
    void cooldownSurvivesRestart() throws Exception {
        UUID player = UUID.randomUUID();
        MutableClock clock = new MutableClock();
        try (Database db = open()) {
            TeleportStore store = new TeleportStore(db);
            Cooldowns before = new Cooldowns(clock);
            Instant end = before.start(player, TeleportCause.HOME, Duration.ofSeconds(90));
            store.saveCooldown(player, TeleportCause.HOME, end.toEpochMilli()).get();
            // An expired row must not come back.
            store.saveCooldown(player, TeleportCause.WARP, clock.instant().minusSeconds(1).toEpochMilli()).get();
        }
        clock.advance(Duration.ofSeconds(30));
        try (Database db = open()) {
            TeleportStore store = new TeleportStore(db);
            List<TeleportStore.CooldownRow> rows = store.loadCooldowns(clock.instant().toEpochMilli()).get();
            assertEquals(1, rows.size());
            Cooldowns after = new Cooldowns(clock);
            rows.forEach(r -> after.restore(r.player(), r.cause(), Instant.ofEpochMilli(r.expiresAt())));
            assertEquals(Duration.ofSeconds(60), after.remaining(player, TeleportCause.HOME));
            assertEquals(Duration.ZERO, after.remaining(player, TeleportCause.WARP));

            store.deleteCooldown(player, TeleportCause.HOME).get();
            assertTrue(store.loadCooldowns(clock.instant().toEpochMilli()).get().isEmpty());
        }
    }

    @Test
    void upsertKeepsOneRowPerCauseAndPurgeRemovesExpired() throws Exception {
        UUID player = UUID.randomUUID();
        try (Database db = open()) {
            TeleportStore store = new TeleportStore(db);
            store.saveCooldown(player, TeleportCause.SPAWN, 1_000L).get();
            store.saveCooldown(player, TeleportCause.SPAWN, 5_000L).get();
            List<TeleportStore.CooldownRow> rows = store.loadCooldowns(0L).get();
            assertEquals(List.of(new TeleportStore.CooldownRow(player, TeleportCause.SPAWN, 5_000L)), rows);
            assertEquals(1, store.purgeCooldowns(6_000L).get());
            assertTrue(store.loadCooldowns(0L).get().isEmpty());
        }
    }

    @Test
    void backHistoryRoundTrip() throws Exception {
        UUID player = UUID.randomUUID();
        BackHistory history = BackHistory.EMPTY
                .push(new BackHistory.Entry(new StoredLocation("world", 1, 2, 3, 0f, 0f), false, 10L), 5)
                .push(new BackHistory.Entry(new StoredLocation("world_nether", 4, 5, 6, 90f, 0f), true, 20L), 5);
        TeleportState state = new TeleportState(history, false);
        try (Database db = open()) {
            new TeleportStore(db).save(player, state).get();
        }
        try (Database db = open()) {
            assertEquals(state, new TeleportStore(db).load(player).get());
        }
    }

    @Test
    void legacySingleSlotRowIsReadAsHistory() throws Exception {
        UUID player = UUID.randomUUID();
        try (Database db = open()) {
            db.update("INSERT INTO {prefix}teleport_players (uuid, back_location, back_death, back_time, accepts_requests) "
                    + "VALUES (?, ?, ?, ?, ?)", player.toString(), "world;1.0;2.0;3.0;0.0;0.0", true, 42L, true).get();
            TeleportState state = new TeleportStore(db).load(player).get();
            assertEquals(1, state.back().size());
            BackHistory.Entry entry = state.back().latest().orElseThrow();
            assertTrue(entry.death());
            assertEquals(42L, entry.recordedAt());
        }
    }
}
