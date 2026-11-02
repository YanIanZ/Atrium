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
    ExecutorService executor;
    final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    @BeforeEach void setup() throws Exception {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        db = new Database(new CoreConfig.Database(), folder, executor, LoggerFactory.getLogger("economy-test"));
        db.migrate("economy", EconomyStore.MIGRATIONS);
        store = new EconomyStore(db, new EconomyConfig(), LoggerFactory.getLogger("economy-test"));
    }
    @AfterEach void close() { db.close(); executor.close(); }
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
        store.offline(a);
        store.deposit(a, BigDecimal.ONE, "offline").join();
        assertNull(store.cachedBalance(a));
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
