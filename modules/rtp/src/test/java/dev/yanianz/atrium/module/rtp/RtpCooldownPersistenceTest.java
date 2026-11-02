package dev.yanianz.atrium.module.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class RtpCooldownPersistenceTest {
    @TempDir
    Path dir;

    private Database open() throws Exception {
        Database db = new Database(new CoreConfig.Database(), dir, Executors.newVirtualThreadPerTaskExecutor(),
                LoggerFactory.getLogger("test"));
        db.migrate("rtp", RtpStore.MIGRATIONS);
        return db;
    }

    @Test
    void perWorldCooldownSurvivesRestart() throws Exception {
        UUID player = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        RtpCooldowns before = new RtpCooldowns(Clock.fixed(t0, ZoneOffset.UTC));
        try (Database db = open()) {
            RtpStore store = new RtpStore(db);
            Instant end = before.start(player, "World", Duration.ofMinutes(5));
            store.saveCooldown(player, "World", end.toEpochMilli()).get();
            store.saveCooldown(player, "world_nether", t0.minusSeconds(1).toEpochMilli()).get();
        }
        Instant t1 = t0.plusSeconds(60);
        try (Database db = open()) {
            List<RtpStore.CooldownRow> rows = new RtpStore(db).loadCooldowns(t1.toEpochMilli()).get();
            assertEquals(1, rows.size());
            RtpCooldowns after = new RtpCooldowns(Clock.fixed(t1, ZoneOffset.UTC));
            rows.forEach(r -> after.restore(r.player(), r.world(), Instant.ofEpochMilli(r.expiresAt())));
            // World names are case-insensitive keys.
            assertEquals(Duration.ofMinutes(4), after.remaining(player, "world"));
            assertEquals(Duration.ZERO, after.remaining(player, "world_nether"));
            assertEquals(Duration.ZERO, after.remaining(UUID.randomUUID(), "world"));
        }
    }

    @Test
    void upsertAndPurge() throws Exception {
        UUID player = UUID.randomUUID();
        try (Database db = open()) {
            RtpStore store = new RtpStore(db);
            store.saveCooldown(player, "world", 1_000L).get();
            store.saveCooldown(player, "WORLD", 9_000L).get();
            assertEquals(List.of(new RtpStore.CooldownRow(player, "world", 9_000L)), store.loadCooldowns(0L).get());
            assertEquals(1, store.purge(10_000L).get());
            assertTrue(store.loadCooldowns(0L).get().isEmpty());
        }
    }

    @Test
    void zeroLengthStartsNothing() {
        RtpCooldowns cooldowns = new RtpCooldowns(Clock.systemUTC());
        UUID player = UUID.randomUUID();
        assertEquals(null, cooldowns.start(player, "world", Duration.ZERO));
        assertEquals(Duration.ZERO, cooldowns.remaining(player, "world"));
    }
}
