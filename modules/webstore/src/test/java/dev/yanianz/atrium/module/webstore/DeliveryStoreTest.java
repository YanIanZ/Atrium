package dev.yanianz.atrium.module.webstore;

import static org.junit.jupiter.api.Assertions.*;

import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class DeliveryStoreTest {
    private static final String UUID = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final int MAX = 3;

    @TempDir Path directory;
    private Database db;
    private DeliveryStore store;

    @BeforeEach void open() throws Exception {
        db = new Database(new CoreConfig.Database(), directory, Runnable::run, LoggerFactory.getLogger(getClass()));
        db.migrate("webstore", DeliveryStore.MIGRATIONS);
        db.migrate("webstore", DeliveryStore.MIGRATIONS); // idempotent
        store = new DeliveryStore(db);
    }

    @AfterEach void close() {
        db.close();
    }

    /** What the website does: INSERT with the defaults for status, attempts and created_at. */
    private long insert(String orderId, String packageId) {
        db.update("INSERT INTO {prefix}webstore_deliveries (order_id, package_id, recipient_uuid, recipient_name,"
                + " buyer_name, gift, gift_message) VALUES (?, ?, ?, ?, ?, ?, ?)", orderId, packageId, UUID, "Steve",
                "Alex", 1, "<red>hi</red>").join();
        return row(orderId).id;
    }

    private record Row(long id, String status, int attempts, String lastError, String deliveredAt) {}

    private Row row(String orderId) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, status, attempts, last_error, delivered_at FROM "
                    + db.table("webstore_deliveries") + " WHERE order_id = ?")) {
                ps.setString(1, orderId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "row " + orderId);
                    return new Row(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5));
                }
            }
        }).join();
    }

    private int claimRows() {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + db.table("webstore_claims"));
                    ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }).join();
    }

    @Test void websiteInsertDefaultsToPendingAndIsListedInIdOrder() {
        insert("b", "vip");
        insert("a", "mvp");
        List<DeliveryStore.Delivery> pending = store.pending(20, MAX).join();
        assertEquals(List.of("b", "a"), pending.stream().map(DeliveryStore.Delivery::orderId).toList());
        DeliveryStore.Delivery first = pending.getFirst();
        assertTrue(first.gift());
        assertEquals("<red>hi</red>", first.giftMessage());
        assertEquals("Alex", first.buyerName());
        assertEquals(0, first.attempts());
        assertNull(row("b").deliveredAt);
        assertEquals(1, store.pending(1, MAX).join().size());
    }

    @Test void onlyOneClaimWins() {
        long id = insert("o1", "vip");
        assertTrue(store.claim(id, MAX, "server-a", 1_000).join());
        assertFalse(store.claim(id, MAX, "server-b", 1_001).join());
        Row r = row("o1");
        assertEquals("processing", r.status);
        assertEquals(1, r.attempts);
        assertEquals(1, claimRows());
        assertTrue(store.pending(20, MAX).join().isEmpty());
    }

    @Test void concurrentClaimsDeliverOnce() throws Exception {
        long id = insert("race", "vip");
        AtomicInteger wins = new AtomicInteger();
        List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String who = "server-" + i;
            futures.add(CompletableFuture.runAsync(() -> {
                if (store.claim(id, MAX, who, System.currentTimeMillis()).join()) {
                    wins.incrementAndGet();
                }
            }));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        assertEquals(1, wins.get());
    }

    @Test void deliveredSetsTimestampAndClearsTheClaim() {
        long id = insert("o2", "vip");
        assertTrue(store.claim(id, MAX, "a", 1).join());
        assertTrue(store.markDelivered(id).join());
        Row r = row("o2");
        assertEquals("delivered", r.status);
        assertNotNull(r.deliveredAt);
        assertNull(r.lastError);
        assertEquals(0, claimRows());
        assertFalse(store.claim(id, MAX, "a", 2).join(), "a delivered row is never claimed again");
        assertFalse(store.markDelivered(id).join());
    }

    @Test void failedAttemptsGoBackToPendingUntilMaxThenFail() {
        long id = insert("o3", "vip");
        for (int attempt = 1; attempt < MAX; attempt++) {
            assertTrue(store.claim(id, MAX, "a", attempt).join());
            assertEquals("pending", store.failAttempt(id, MAX, "boom " + attempt).join());
            assertEquals(attempt, row("o3").attempts);
            assertEquals("boom " + attempt, row("o3").lastError);
        }
        assertTrue(store.claim(id, MAX, "a", 99).join());
        assertEquals("failed", store.failAttempt(id, MAX, "x".repeat(400)).join());
        Row r = row("o3");
        assertEquals("failed", r.status);
        assertEquals(MAX, r.attempts);
        assertEquals(255, r.lastError.length());
        assertEquals(0, claimRows());
        assertFalse(store.claim(id, MAX, "a", 100).join());
        assertNull(store.failAttempt(id, MAX, "not processing").join());
    }

    @Test void failPendingOnlyTouchesPendingRows() {
        long id = insert("o4", "unknown");
        long other = insert("o5", "vip");
        assertTrue(store.claim(other, MAX, "a", 1).join());
        assertFalse(store.failPending(other, "unknown package").join(), "a claimed row is left to its claimer");
        assertTrue(store.failPending(id, "unknown package: unknown").join());
        assertEquals("failed", row("o4").status);
        assertEquals("unknown package: unknown", row("o4").lastError);
        assertEquals(0, row("o4").attempts);
    }

    @Test void retryRequeuesFailedOrdersOnly() {
        long id = insert("o6", "vip");
        store.failPending(id, "unknown package").join();
        db.update("UPDATE {prefix}webstore_deliveries SET attempts = 3 WHERE id = ?", id).join();
        DeliveryStore.RetryResult result = store.retry("o6").join();
        assertTrue(result.retried());
        Row r = row("o6");
        assertEquals("pending", r.status);
        assertEquals(0, r.attempts);
        assertNull(r.lastError);
        assertEquals(new DeliveryStore.RetryResult(false, "pending"), store.retry("o6").join());
        assertEquals(new DeliveryStore.RetryResult(false, null), store.retry("missing").join());
    }

    @Test void staleClaimsAreRecoveredButFreshOnesAreKept() {
        long stale = insert("stale", "vip");
        long fresh = insert("fresh", "vip");
        long orphan = insert("orphan", "vip");
        assertTrue(store.claim(stale, MAX, "dead-server", 1_000).join());
        assertTrue(store.claim(fresh, MAX, "live-server", 50_000).join());
        db.update("UPDATE {prefix}webstore_deliveries SET status = 'processing' WHERE id = ?", orphan).join();
        assertEquals(2, store.recoverStale(10_000, MAX).join());
        assertEquals("pending", row("stale").status);
        assertTrue(row("stale").lastError.startsWith("claim expired"));
        assertEquals(1, row("stale").attempts, "the interrupted attempt still counts");
        assertEquals("pending", row("orphan").status);
        assertEquals("processing", row("fresh").status);
        assertEquals(1, claimRows());
        // The slow server finishes after its row was requeued: it is still recorded as delivered, once.
        assertTrue(store.markDelivered(stale).join());
        assertEquals("delivered", row("stale").status);
    }

    @Test void staleClaimWithoutAttemptsLeftFails() {
        long id = insert("last", "vip");
        db.update("UPDATE {prefix}webstore_deliveries SET attempts = ? WHERE id = ?", MAX - 1, id).join();
        assertTrue(store.claim(id, MAX, "dead", 1).join());
        assertEquals(1, store.recoverStale(10, MAX).join());
        assertEquals("failed", row("last").status);
    }

    @Test void pendingRowsWithoutAttemptsLeftAreFailedBySweep() {
        insert("lowered", "vip");
        db.update("UPDATE {prefix}webstore_deliveries SET attempts = 5 WHERE order_id = 'lowered'").join();
        assertTrue(store.pending(20, MAX).join().isEmpty());
        assertEquals(1, store.recoverStale(0, MAX).join());
        assertEquals("failed", row("lowered").status);
        assertEquals("no attempts left", row("lowered").lastError);
    }

    @Test void countsEveryStatus() {
        long a = insert("c1", "vip");
        insert("c2", "vip");
        long c = insert("c3", "vip");
        store.claim(a, MAX, "x", 1).join();
        store.markDelivered(a).join();
        store.failPending(c, "nope").join();
        assertEquals(Map.of("pending", 1, "processing", 0, "delivered", 1, "failed", 1), store.counts().join());
        assertEquals(List.of("pending", "processing", "delivered", "failed"), List.copyOf(store.counts().join().keySet()));
    }

    @Test void truncatesErrors() {
        assertEquals("unknown error", DeliveryStore.truncate(null));
        assertEquals("ok", DeliveryStore.truncate("  ok "));
        assertEquals(255, DeliveryStore.truncate("y".repeat(300)).length());
    }
}
