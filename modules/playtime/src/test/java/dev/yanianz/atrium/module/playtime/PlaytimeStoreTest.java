package dev.yanianz.atrium.module.playtime;

import static org.junit.jupiter.api.Assertions.*;

import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class PlaytimeStoreTest {
    @TempDir Path folder;
    Database db;
    PlaytimeStore store;
    ExecutorService executor;
    final UUID player = UUID.randomUUID();

    @BeforeEach void setup() throws Exception {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        db = new Database(new CoreConfig.Database(), folder, executor, LoggerFactory.getLogger("playtime-test"));
        db.migrate("playtime", PlaytimeStore.MIGRATIONS);
        db.update("CREATE TABLE {prefix}users (uuid CHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(16) NOT NULL)").join();
        db.update("INSERT INTO {prefix}users (uuid, name) VALUES (?, ?)", player.toString(), "Tester").join();
        store = new PlaytimeStore(db);
    }

    @AfterEach void close() {
        db.close();
        executor.close();
    }

    @Test void accumulatesSecondsPerDayAtomically() {
        LocalDate today = LocalDate.of(2026, 11, 2);
        store.addSeconds(player, today, 10).join();
        store.addSeconds(player, today, 25).join();
        store.addSeconds(player, today.plusDays(1), 5).join();
        assertEquals(35, store.secondsOn(player, today).join());
        assertEquals(40, store.total(player).join());
    }

    @Test void setTotalReplacesEveryDayBucketInOneTransaction() {
        LocalDate today = LocalDate.of(2026, 11, 2);
        store.addSeconds(player, today.minusDays(3), 100).join();
        store.addSeconds(player, today, 30).join();

        store.setTotal(player, today, 500).join();
        assertEquals(500, store.total(player).join());
        assertEquals(500, store.secondsOn(player, today).join());
        assertEquals(0, store.secondsOn(player, today.minusDays(3)).join(), "old buckets are removed");

        store.setTotal(player, today, 0).join();
        assertEquals(0, store.total(player).join(), "zero removes every bucket");
    }

    @Test void claimLifecycleIsDurableAndNeverRegresses() {
        String id = "hourly";
        assertTrue(store.beginClaim(player, id, 3).join().isPresent());
        assertEquals(3, store.pending(player).join().getFirst().watermark());
        assertTrue(store.pending(player).join().stream().anyMatch(c -> c.rewardId().equals(id)));

        store.completeClaim(player, id, 3).join();
        assertTrue(store.pending(player).join().isEmpty());
        assertEquals(3, store.component.loader().load(player).join().watermark(id));

        assertTrue(store.beginClaim(player, id, 3).join().isEmpty(), "a completed mark is never paid twice");
        assertTrue(store.beginClaim(player, id, 2).join().isEmpty(), "old marks cannot move the watermark backwards");
        assertTrue(store.beginClaim(player, id, 4).join().isPresent());
        store.releaseClaim(player, id, 4).join();
        assertTrue(store.pending(player).join().isEmpty());
    }

    /** Regression: a retried claim must remember which delivery steps already ran. */
    @Test void claimProgressIsDurableAndResetsOnlyForANewWatermark() {
        String id = "mixed";
        var claim = store.beginClaim(player, id, 1).join().orElseThrow();
        assertFalse(claim.progress().moneyDone());
        assertEquals(0, claim.progress().commandsDone());

        store.markMoneyDone(player, id, 1).join();
        store.markCommandsDone(player, id, 1, 1).join();
        var retried = store.beginClaim(player, id, 1).join().orElseThrow();
        assertTrue(retried.progress().moneyDone(), "the money is not deposited a second time");
        assertEquals(1, retried.progress().commandsDone(), "the first command is not dispatched again");

        store.markCommandsDone(player, id, 1, 0).join();
        assertEquals(1, store.beginClaim(player, id, 1).join().orElseThrow().progress().commandsDone(),
                "command progress never moves backwards");

        var next = store.beginClaim(player, id, 2).join().orElseThrow();
        assertFalse(next.progress().moneyDone(), "a new claim starts from the first step");
        assertEquals(0, next.progress().commandsDone());

        store.completeClaim(player, id, 2).join();
        assertTrue(store.beginClaim(player, id, 2).join().isEmpty());
    }

    @Test void advanceToSkipsTheIntervalsAnAdministrativeImportCovered() {
        String id = "hourly";
        assertTrue(store.beginClaim(player, id, 1).join().isPresent(), "a delivery from before the import");
        store.advanceTo(player, id, 2_000).join();

        assertEquals(2_000, store.component.loader().load(player).join().watermark(id));
        assertTrue(store.pending(player).join().isEmpty(),
                "a pending claim below the imported total is superseded instead of being paid later");

        store.advanceTo(player, id, 1_500).join();
        assertEquals(2_000, store.component.loader().load(player).join().watermark(id),
                "watermarks never move backwards");
        store.advanceTo(player, id, 0).join();
        assertEquals(2_000, store.component.loader().load(player).join().watermark(id));
    }

    @Test void advanceToLeavesANewerPendingDeliveryAlone() {
        String id = "hourly";
        assertTrue(store.beginClaim(player, id, 5).join().isPresent());
        store.advanceTo(player, id, 3).join();
        assertTrue(store.pending(player).join().stream().anyMatch(c -> c.rewardId().equals(id)),
                "a delivery above the new watermark is still owed");
    }

    @Test void baselineNeverOverwritesNewerStateOrPendingDelivery() {
        String id = "repeating";
        store.markDone(player, id, 10).join();
        store.markDone(player, id, 5).join();
        assertEquals(10, store.component.loader().load(player).join().watermark(id));

        assertTrue(store.beginClaim(player, id, 11).join().isPresent());
        store.markDone(player, id, 12).join();
        assertTrue(store.pending(player).join().stream().anyMatch(c -> c.rewardId().equals(id)),
                "a baseline must not discard a pending delivery");
    }

    @Test void leaderboardAggregatesPerPeriodAndKeepsNames() {
        UUID other = UUID.randomUUID();
        db.update("INSERT INTO {prefix}users (uuid, name) VALUES (?, ?)", other.toString(), "Other").join();
        LocalDate today = LocalDate.of(2026, 11, 2);
        store.addSeconds(player, today, 60).join();
        store.addSeconds(other, today, 90).join();
        store.addSeconds(player, today.minusDays(10), 500).join();

        var day = store.top(today, 10).join();
        assertEquals(2, day.size());
        assertEquals("Other", day.getFirst().name());
        assertEquals(90, day.getFirst().seconds());
        var all = store.top(null, 10).join();
        assertEquals("Tester", all.getFirst().name());
        assertEquals(560, all.getFirst().seconds());
    }
}
