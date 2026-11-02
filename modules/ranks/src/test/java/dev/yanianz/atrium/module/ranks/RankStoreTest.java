package dev.yanianz.atrium.module.ranks;

import static org.junit.jupiter.api.Assertions.*;

import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class RankStoreTest {
    @TempDir Path folder;
    Database db;
    RankStore store;
    ExecutorService executor;
    final UUID player = UUID.randomUUID();

    @BeforeEach void setup() throws Exception {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        db = new Database(new CoreConfig.Database(), folder, executor, LoggerFactory.getLogger("ranks-test"));
        db.migrate("ranks", RankStore.MIGRATIONS);
        store = new RankStore(db, () -> "member");
    }

    @AfterEach void close() {
        db.close();
        executor.close();
    }

    @Test void missingRowsUseTheStartingRank() {
        assertEquals("member", store.storedRank(player).join());
        assertNull(store.component.loader().load(player).join());
        assertEquals("member", store.component.defaults().get().rankId());
    }

    @Test void persistsRankAndRewardedLedgerTogether() {
        RankProfile profile = new RankProfile("veteran", Set.of("veteran", "elite"));
        store.component.saver().save(player, profile).join();
        RankProfile loaded = store.component.loader().load(player).join();
        assertEquals("veteran", loaded.rankId());
        assertEquals(Set.of("veteran", "elite"), loaded.rewardedRanks());
        assertEquals("veteran", store.storedRank(player).join());
    }

    @Test void rewardedRanksAreIrreversibleOnlyByMarkingAgain() {
        RankProfile profile = RankProfile.initial("member");
        assertFalse(profile.isRewarded("veteran"));
        RankProfile rewarded = profile.withRank("veteran").markRewarded("veteran");
        assertTrue(rewarded.isRewarded("veteran"));
        assertSame(rewarded, rewarded.markRewarded("veteran"), "marking twice must be idempotent");
    }
}
