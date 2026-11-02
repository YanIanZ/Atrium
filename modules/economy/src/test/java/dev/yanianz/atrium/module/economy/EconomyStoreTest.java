package dev.yanianz.atrium.module.economy;

import static org.junit.jupiter.api.Assertions.*;
import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class EconomyStoreTest {
    @TempDir Path folder;
    Database db;
    EconomyStore store;
    GatedExecutor executor;
    final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    @BeforeEach void setup() throws Exception {
        executor = new GatedExecutor();
        db = new Database(new CoreConfig.Database(), folder, executor, LoggerFactory.getLogger("economy-test"));
        db.migrate("economy", EconomyStore.MIGRATIONS);
        store = new EconomyStore(db, new EconomyConfig(), LoggerFactory.getLogger("economy-test"));
    }
    @AfterEach void close() { db.close(); executor.shutdown(); }
    @Test void cancellingCallerFutureDoesNotReleaseInternalQueue() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CompletableFuture<Void> blocker = db.query(c -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); throw new java.sql.SQLException(e);
            }
            return null;
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            var read = store.balance(a);
            read.cancel(false);
            assertFalse(store.close().isDone(), "Queue must drain the real database work despite caller cancellation");
        } finally { release.countDown(); blocker.join(); store.close().join(); }
    }
    @Test void readsNeverCreateAccounts() {
        assertEquals(new BigDecimal("0.00"), store.balance(a).join());
        assertFalse(store.exists(a).join());
    }
    @Test void concurrentWithdrawalsCannotDoubleSpend() {
        store.set(a, new BigDecimal("100"), "seed").join();
        var first = store.withdraw(a, new BigDecimal("80"), "one");
        var second = store.withdraw(a, new BigDecimal("80"), "two");
        assertNotEquals(first.join().success(), second.join().success());
        assertEquals(new BigDecimal("20.00"), store.balance(a).join());
    }
    @Test void transferConservesExactLargeDecimals() {
        store.set(a, new BigDecimal("99999999999999999990"), "seed").join();
        assertTrue(store.transfer(a, b, new BigDecimal("0.01"), "pay").join().success());
        assertEquals(new BigDecimal("99999999999999999989.99"), store.balance(a).join());
        assertEquals(new BigDecimal("0.01"), store.balance(b).join());
    }
    @Test void failedLedgerWriteRollsBackBothAccountsAndCache() {
        store.set(a, new BigDecimal("10"), "seed").join();
        store.ensure(b).join();
        store.online(a).join();
        db.update("DROP TABLE {prefix}economy_transactions").join();
        assertFalse(store.transfer(a, b, BigDecimal.ONE, "pay").join().success());
        assertEquals(new BigDecimal("10.00"), store.balance(a).join());
        assertEquals(new BigDecimal("0.00"), store.balance(b).join());
        assertEquals(new BigDecimal("10.00"), store.cachedBalance(a));
    }
    @Test void failedTransferDoesNotCreateEitherAccount() {
        assertFalse(store.transfer(a, b, BigDecimal.ONE, "pay").join().success());
        assertFalse(store.exists(a).join());
        assertFalse(store.exists(b).join());
    }
    @Test void debtFloorAndOpeningBalanceAreEnforced() {
        EconomyConfig config = new EconomyConfig();
        config.startingBalance = "5"; config.minBalance = "-10";
        store = new EconomyStore(db, config, LoggerFactory.getLogger("economy-test"));
        assertEquals(new BigDecimal("5.00"), store.balance(a).join());
        assertFalse(store.exists(a).join());
        assertTrue(store.withdraw(a, new BigDecimal("15"), "debt").join().success());
        assertFalse(store.withdraw(a, new BigDecimal("0.01"), "overdraft").join().success());
        assertEquals(new BigDecimal("-10.00"), store.balance(a).join());
        assertEquals(2, store.history(a).join().size());
    }
    @Test void leaderboardUsesNumericOrderingForTextBalances() throws Exception {
        db.migrate("users", dev.yanianz.atrium.core.user.UserManager.MIGRATIONS);
        store.set(a, new BigDecimal("9"), "seed").join();
        store.set(b, new BigDecimal("10"), "seed").join();
        var leaders = store.leaderboard().join();
        assertEquals(b, leaders.getFirst().uuid());
        assertEquals(a, leaders.getLast().uuid());
        assertThrows(UnsupportedOperationException.class, leaders::clear);
    }
    @Test void cacheTracksCommittedWritesAndIsRemovedOnQuit() {
        store.online(a).join();
        store.deposit(a, new BigDecimal("10"), "give").join();
        store.withdraw(a, new BigDecimal("3"), "take").join();
        assertEquals(new BigDecimal("7.00"), store.cachedBalance(a));
        store.offline(a).join();
        store.deposit(a, BigDecimal.ONE, "offline").join();
        assertNull(store.cachedBalance(a));
    }
    @Test void warmBeforeLoginCachesTheStartingBalanceAndQuitEvicts() {
        assertFalse(store.isOnline(a));
        store.warm(a).join();
        assertEquals(new BigDecimal("0.00"), store.cachedRead(a), "The warm must publish before the player is in game");
        assertFalse(store.isOnline(a), "Warming alone must not mark a player online");
        store.online(a).join();
        assertTrue(store.isOnline(a));
        assertEquals(new BigDecimal("0.00"), store.cachedBalance(a));
        store.deposit(a, new BigDecimal("5"), "give").join();
        assertEquals(new BigDecimal("5.00"), store.cachedRead(a));
        store.offline(a).join();
        assertFalse(store.isOnline(a));
        assertNull(store.cachedBalance(a));
        assertNull(store.cachedRead(a), "Quit evicts the recent offline read too");
    }
    @Test void cacheChangesOnlyAfterTheCommitCompletes() throws Exception {
        store.online(a).join();
        assertEquals(new BigDecimal("0.00"), store.cachedRead(a));
        executor.hold();
        var pending = store.deposit(a, new BigDecimal("10"), "give");
        assertTrue(executor.blocked.await(5, TimeUnit.SECONDS), "The write must be waiting on storage");
        assertEquals(new BigDecimal("0.00"), store.cachedRead(a), "A queued write must not touch the cache");
        executor.release();
        assertTrue(pending.get(5, TimeUnit.SECONDS).success());
        assertEquals(new BigDecimal("10.00"), store.cachedRead(a));
    }
    @Test void offlineReadValuesAreBoundedAndDroppedWhenThePlayerGoesOnline() {
        for (int i = 0; i < 5000; i++) store.rememberRead(uuid("read-" + i), BigDecimal.ONE);
        assertNotNull(store.cachedRead(uuid("read-4999")));
        assertNull(store.cachedRead(uuid("read-0")), "The bounded read cache drops the oldest value");
        store.rememberRead(a, new BigDecimal("3"));
        assertEquals(new BigDecimal("3"), store.cachedRead(a));
        store.online(a).join();
        assertEquals(new BigDecimal("0.00"), store.cachedRead(a), "The committed balance wins once the player is online");
    }
    @Test void commitsInvalidateAnOfflineReadValue() {
        store.rememberRead(a, new BigDecimal("1"));
        assertTrue(store.deposit(a, new BigDecimal("4"), "give").join().success());
        assertNull(store.cachedRead(a), "A commit must invalidate the offline read value instead of serving it");
        assertEquals(new BigDecimal("4.00"), store.balance(a).join());
    }
    private static UUID uuid(String seed) { return UUID.nameUUIDFromBytes(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }

    /** Executes database work on virtual threads, optionally held so a test can observe pre-commit state. */
    static final class GatedExecutor implements Executor {
        private final ExecutorService delegate = Executors.newVirtualThreadPerTaskExecutor();
        private final CountDownLatch blocked = new CountDownLatch(1);
        private volatile CountDownLatch gate;
        @Override public void execute(Runnable task) {
            delegate.execute(() -> {
                CountDownLatch held = gate;
                if (held != null) {
                    blocked.countDown();
                    try { held.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
                task.run();
            });
        }
        void hold() { gate = new CountDownLatch(1); }
        void release() {
            CountDownLatch held = gate;
            gate = null;
            if (held != null) held.countDown();
        }
        void shutdown() { release(); delegate.shutdown(); }
    }
    @Test void invalidAmountsAndSelfTransferDoNotCreateAnAccount() {
        assertFalse(store.deposit(a, new BigDecimal("-0.001"), "invalid").join().success());
        assertFalse(store.transfer(a, a, BigDecimal.ONE, "self").join().success());
        assertFalse(store.exists(a).join());
    }
    @Test void receiverCapRejectsWholeTransfer() {
        EconomyConfig config = new EconomyConfig(); config.maxBalance = "10";
        store = new EconomyStore(db, config, LoggerFactory.getLogger("economy-test"));
        store.set(a, BigDecimal.TEN, "seed").join();
        store.set(b, BigDecimal.TEN, "seed").join();
        assertFalse(store.transfer(a, b, BigDecimal.ONE, "pay").join().success());
        assertEquals(new BigDecimal("10.00"), store.balance(a).join());
    }
}
