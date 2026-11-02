package dev.yanianz.atrium.module.economy;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** The Vault read policy, tested without a server: it must never block where blocking would hurt. */
class VaultReadsTest {
    private final EconomyStore.Key account = new EconomyStore.Key(UUID.randomUUID(), WorldGroups.DEFAULT_KEY);
    private final FakeStore store = new FakeStore();

    private VaultReads reads(boolean tickThread) {
        return new VaultReads(store, LoggerFactory.getLogger("economy-test"), 5000, () -> tickThread, () -> true);
    }

    @Test void decisionPrefersTheCacheAndNeverWaitsOnTickThreadsOrOnlinePlayers() {
        assertEquals(VaultReads.Mode.CACHE, VaultReads.decide(true, false, true));
        assertEquals(VaultReads.Mode.CACHE, VaultReads.decide(true, true, false));
        assertEquals(VaultReads.Mode.DEFER, VaultReads.decide(false, true, false));
        assertEquals(VaultReads.Mode.DEFER, VaultReads.decide(false, false, true));
        assertEquals(VaultReads.Mode.WAIT, VaultReads.decide(false, false, false));
    }

    @Test void onlineReadAnswersTheCacheWithoutTouchingStorage() {
        store.cached = new BigDecimal("12.50");
        store.online = true;
        assertEquals(new BigDecimal("12.50"), reads(false).balance(account));
        assertEquals(0, store.loads.get());
    }

    @Test void onlineReadNeverBlocksOnAnUnresolvedLoad() {
        store.online = true;
        BigDecimal value = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> reads(false).balance(account));
        assertEquals(BigDecimal.ZERO, value, "An uncached online read must answer 0 instead of waiting");
        assertEquals(1, store.loads.get(), "The read still starts the background load");
    }

    @Test void tickThreadReadAnswersZeroAndSharesOneLoad() {
        VaultReads reads = reads(true);
        BigDecimal first = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> reads.balance(account));
        BigDecimal second = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> reads.balance(account));
        assertEquals(BigDecimal.ZERO, first);
        assertEquals(BigDecimal.ZERO, second);
        assertEquals(1, store.loads.get(), "Concurrent deferred reads must not queue duplicate loads");
    }

    @Test void tickThreadReadUsesARecentValueWhenItExists() {
        store.cached = new BigDecimal("4");
        assertEquals(new BigDecimal("4"), reads(true).balance(account));
        assertEquals(0, store.loads.get());
    }

    @Test void regularThreadWaitsForTheOfflineValue() {
        store.load.complete(new BigDecimal("12.50"));
        assertEquals(new BigDecimal("12.50"), reads(false).balance(account));
    }

    @Test void deferredValueIsRememberedOnlyAfterTheLoadCompletes() throws Exception {
        store.online = true;
        reads(false).balance(account);
        assertNull(store.remembered.get(), "Nothing may be cached before storage settles");
        store.load.complete(new BigDecimal("7"));
        assertTrue(store.await(5, TimeUnit.SECONDS));
        assertEquals(new BigDecimal("7"), store.remembered.get());
    }

    /** Storage stand-in whose load stays unresolved until a test completes it. */
    private static final class FakeStore implements VaultReads.Store {
        volatile BigDecimal cached;
        volatile boolean online;
        final CompletableFuture<BigDecimal> load = new CompletableFuture<>();
        final AtomicInteger loads = new AtomicInteger();
        final AtomicReference<BigDecimal> remembered = new AtomicReference<>();
        private final CountDownLatch rememberedLatch = new CountDownLatch(1);

        @Override public BigDecimal cachedRead(EconomyStore.Key id) { return cached; }
        @Override public boolean isOnline(EconomyStore.Key id) { return online; }
        @Override public CompletableFuture<BigDecimal> balance(EconomyStore.Key id) { loads.incrementAndGet(); return load; }
        @Override public void rememberRead(EconomyStore.Key id, BigDecimal value) {
            remembered.set(value);
            rememberedLatch.countDown();
        }
        boolean await(long time, TimeUnit unit) throws InterruptedException { return rememberedLatch.await(time, unit); }
    }
}
