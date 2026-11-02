package dev.yanianz.atrium.module.kits;

import static org.junit.jupiter.api.Assertions.*;

import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class KitUsageTest {
    private static final long NOW = 1_800_000_000_000L;

    @Test
    void neverClaimedIsAvailable() {
        var a = KitUsage.check(null, 3600, true, NOW, false, false);
        assertTrue(a.available());
        assertEquals(Duration.ZERO, a.remaining());
    }

    @Test
    void cooldownRemainingIsRoundedUpToSeconds() {
        var claim = new KitUsage.Claim(NOW - 1_000_500, 1); // 1000.5 s ago
        var a = KitUsage.check(claim, 3600, false, NOW, false, false);
        assertEquals(KitUsage.Status.COOLDOWN, a.status());
        assertEquals(Duration.ofSeconds(2600), a.remaining()); // 2599.5 s -> 2600 s
        var exact = KitUsage.check(new KitUsage.Claim(NOW - 3_600_000, 1), 3600, false, NOW, false, false);
        assertTrue(exact.available(), "available exactly when the cooldown ends");
        var almost = KitUsage.check(new KitUsage.Claim(NOW - 3_599_999, 1), 3600, false, NOW, false, false);
        assertEquals(Duration.ofSeconds(1), almost.remaining());
    }

    @Test
    void zeroCooldownAndBypass() {
        var claim = new KitUsage.Claim(NOW, 5);
        assertTrue(KitUsage.check(claim, 0, false, NOW, false, false).available());
        assertTrue(KitUsage.check(claim, 3600, false, NOW, true, false).available());
    }

    @Test
    void maxUsesLimitIsEnforcedAndCanBeBypassed() {
        var once = new KitUsage.Claim(NOW, 1);
        assertEquals(KitUsage.Status.MAX_USES,
                KitUsage.check(once, 0, false, 1, NOW, false, false, false).status());
        assertEquals(KitUsage.Status.AVAILABLE,
                KitUsage.check(once, 0, false, 2, NOW, false, false, false).status());
        assertTrue(KitUsage.check(once, 0, false, 1, NOW, false, false, true).available());
        assertEquals(KitUsage.Status.MAX_USES,
                KitUsage.check(null, 0, false, 0, NOW, false, false, false).status());
        assertTrue(KitUsage.check(null, 0, false, -1, NOW, false, false, false).available());
    }

    @Test
    void usageResetKeepsTheCooldownButRemovesTheLimit() {
        var claim = new KitUsage.Claim(NOW - 10_000, 5);
        KitUsage usage = new KitUsage(Map.of("starter", claim));
        KitUsage reset = usage.resetUses("starter");
        assertEquals(new KitUsage.Claim(NOW - 10_000, 0), reset.claim("starter"));
        assertEquals(KitUsage.Status.COOLDOWN,
                KitUsage.check(reset.claim("starter"), 60, false, 2, NOW, false, false, false).status());
        assertEquals(KitUsage.Status.AVAILABLE,
                KitUsage.check(reset.claim("starter"), 0, false, 2, NOW, false, false, false).status());
    }

    @Test
    void cooldownResetKeepsTheUseCount() {
        KitUsage usage = new KitUsage(Map.of("starter", new KitUsage.Claim(NOW - 10_000, 5)));
        KitUsage reset = usage.resetCooldown("starter");
        assertEquals(new KitUsage.Claim(0, 5), reset.claim("starter"));
        assertTrue(KitUsage.check(reset.claim("starter"), 3600, true, 5, NOW, false, false, false).status()
                == KitUsage.Status.ALREADY_CLAIMED);
    }

    @Test
    void bulkResetsTouchEveryClaim() {
        KitUsage usage = new KitUsage(Map.of(
                "a", new KitUsage.Claim(NOW, 2),
                "b", new KitUsage.Claim(NOW - 1, 3)));
        KitUsage cooldowns = usage.resetCooldown(null);
        assertEquals(new KitUsage.Claim(0, 2), cooldowns.claim("a"));
        assertEquals(new KitUsage.Claim(0, 3), cooldowns.claim("b"));
        KitUsage uses = usage.resetUses(null);
        assertEquals(new KitUsage.Claim(NOW, 0), uses.claim("a"));
        assertEquals(new KitUsage.Claim(NOW - 1, 0), uses.claim("b"));
    }

    @Test
    void chargeDecisionHonoursFreeKitsAndBypass() {
        Kit free = kit(BigDecimal.ZERO);
        Kit paid = kit(new BigDecimal("12.5"));
        assertEquals(BigDecimal.ZERO, free.chargeTo(false));
        assertEquals(BigDecimal.ZERO, paid.chargeTo(true));
        assertEquals(new BigDecimal("12.5"), paid.chargeTo(false));
    }

    private static Kit kit(BigDecimal cost) {
        return new Kit("starter", 60, false, false, cost, new byte[0], null, "", -1, NOW);
    }

    @Test
    void oneTimeKitsStayClaimedUnlessBypassed() {
        var claim = new KitUsage.Claim(NOW - Duration.ofDays(365).toMillis(), 1);
        assertEquals(KitUsage.Status.ALREADY_CLAIMED, KitUsage.check(claim, 0, true, NOW, true, false).status());
        assertTrue(KitUsage.check(claim, 0, true, NOW, false, true).available());
        // Bypassing one-time still respects a cooldown.
        var recent = new KitUsage.Claim(NOW - 10_000, 1);
        assertEquals(KitUsage.Status.COOLDOWN, KitUsage.check(recent, 60, true, NOW, false, true).status());
    }

    @Test
    void futureTimestampIsClampedToNow() {
        // Clock moved backwards by a day: the full cooldown applies, not a day extra.
        var claim = new KitUsage.Claim(NOW + Duration.ofDays(1).toMillis(), 1);
        var a = KitUsage.check(claim, 600, false, NOW, false, false);
        assertEquals(Duration.ofSeconds(600), a.remaining());
    }

    @Test
    void hugeCooldownDoesNotOverflow() {
        var claim = new KitUsage.Claim(NOW, 1);
        var a = KitUsage.check(claim, Long.MAX_VALUE / 10, false, NOW, false, false);
        assertEquals(KitUsage.Status.COOLDOWN, a.status());
        assertTrue(a.remaining().isPositive());
    }

    @Test
    void claimAndUndo() {
        KitUsage usage = KitUsage.EMPTY.claimed("starter", NOW);
        assertEquals(new KitUsage.Claim(NOW, 1), usage.claim("starter"));
        KitUsage twice = usage.claimed("starter", NOW + 5);
        assertEquals(new KitUsage.Claim(NOW + 5, 2), twice.claim("starter"));
        assertEquals(usage, twice.with("starter", usage.claim("starter")));
        assertNull(usage.with("starter", null).claim("starter"));
    }

    @Test
    void kitNames() {
        assertTrue(Kit.validName("starter"));
        assertTrue(Kit.validName("Vip_2-x"));
        assertFalse(Kit.validName("others"));
        assertFalse(Kit.validName("bypass"));
        assertFalse(Kit.validName("a.b"));
        assertFalse(Kit.validName(""));
        assertFalse(Kit.validName("x".repeat(33)));
        assertThrows(IllegalArgumentException.class, () -> new Kit("k", -1, false, false, BigDecimal.ZERO, new byte[0], null, "", -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Kit("k", 1, false, false, new BigDecimal("-1"), new byte[0], null, "", -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Kit("k", 1, false, false, BigDecimal.ZERO, new byte[0], null, "bad permission", -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Kit("k", 1, false, false, BigDecimal.ZERO, new byte[0], null, "", -2, 0));
        assertTrue(Kit.validPermission("atrium.kit.starter"));
        assertFalse(Kit.validPermission("bad node"));
        assertEquals("atrium.kit.starter", kit(BigDecimal.ZERO).permission());
        assertEquals("vip.kits.weekly", new Kit("weekly", 0, false, false, BigDecimal.ZERO, new byte[0], null,
                "VIP.Kits.Weekly", -1, 0).permission());
    }

    @Test
    void itemBlobRoundTripsAndRejectsGarbage() {
        List<byte[]> slots = new ArrayList<>();
        for (int i = 0; i < KitItems.SIZE; i++) slots.add(i % 7 == 0 ? new byte[] {(byte) i, 2, 3} : null);
        List<byte[]> back = KitItems.decode(KitItems.encode(slots));
        assertEquals(KitItems.SIZE, back.size());
        for (int i = 0; i < KitItems.SIZE; i++) {
            if (slots.get(i) == null) assertNull(back.get(i));
            else assertArrayEquals(slots.get(i), back.get(i));
        }
        byte[] blob = KitItems.encode(slots);
        assertThrows(IllegalArgumentException.class, () -> KitItems.decode(Arrays.copyOf(blob, blob.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> KitItems.decode(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}));
    }

    @Test
    void previewLayoutMapsEverySlotOnce() {
        Set<Integer> used = new HashSet<>();
        for (int i = 0; i < KitItems.SIZE; i++) {
            int slot = KitLayout.displaySlot(i);
            assertTrue(slot >= 0 && slot < 45, "slot " + i + " must stay above the navigation bar");
            assertTrue(used.add(slot), "duplicate display slot for " + i);
        }
        assertFalse(used.contains(KitLayout.INFO));
        assertEquals(36, KitLayout.displaySlot(39)); // helmet first in the equipment row
        assertEquals(39, KitLayout.displaySlot(36)); // boots
        assertEquals(40, KitLayout.displaySlot(KitItems.OFFHAND));
        assertEquals(27, KitLayout.displaySlot(0)); // hotbar row
        assertEquals(0, KitLayout.displaySlot(9)); // main inventory starts top-left
        assertEquals(-1, KitLayout.displaySlot(KitItems.SIZE));
    }

    @Test
    void storePersistsKitsAndUsage(@TempDir Path folder) throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Database db = new Database(new CoreConfig.Database(), folder, executor, LoggerFactory.getLogger("kits-test"));
            try {
                db.migrate("kits", KitStore.MIGRATIONS);
                KitStore store = new KitStore(db);
                Kit kit = new Kit("starter", 3600, true, true, new BigDecimal("12.5"), new byte[] {9, 8, 7}, null, "", 3, 42);
                assertTrue(store.createKit(kit).join());
                assertFalse(store.createKit(kit).join(), "duplicate names are refused");
                assertEquals(List.of(kit), store.loadKits().join());
                Kit stored = store.updateKit(kit.withCooldown(60, 43)).join().orElseThrow();
                assertEquals(1, stored.version());
                assertEquals(60, store.loadKits().join().getFirst().cooldownSeconds());
                assertEquals(1, store.loadKits().join().getFirst().version());

                UUID player = UUID.randomUUID();
                assertNull(store.loadUsage(player).join());
                KitUsage usage = KitUsage.EMPTY.claimed("starter", NOW).claimed("other", NOW + 1);
                store.saveUsage(player, usage).join();
                assertEquals(usage, store.loadUsage(player).join());
                store.saveUsage(player, usage.with("other", null)).join();
                assertEquals(usage.with("other", null), store.loadUsage(player).join());

                assertFalse(store.deleteKit("starter", 0).join(), "a stale delete removes nothing");
                assertEquals(1, store.loadKits().join().size());
                assertTrue(store.deleteKit("starter", 1).join());
                assertNull(store.loadUsage(player).join(), "deleting a kit removes its usage");
                assertTrue(store.loadKits().join().isEmpty());
            } finally {
                db.close();
            }
        }
    }

    @Test
    void concurrentEditorSavesDoNotLoseItems(@TempDir Path folder) throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Database db = new Database(new CoreConfig.Database(), folder, executor, LoggerFactory.getLogger("kits-test"));
            try {
                db.migrate("kits", KitStore.MIGRATIONS);
                KitStore store = new KitStore(db);
                Kit opened = new Kit("starter", 0, false, false, BigDecimal.ZERO, new byte[] {1}, null, "", -1, 1);
                assertTrue(store.createKit(opened).join());
                // A and B open the same version; A adds diamonds and saves first.
                Kit byA = opened.withItems(new byte[] {1, 64}, 2);
                Kit byB = opened.withCost(BigDecimal.ONE, 3);
                assertTrue(store.updateKit(byA).join().isPresent());
                assertTrue(store.updateKit(byB).join().isEmpty(), "B's stale save must be refused");
                Kit now = store.loadKit("starter").join().orElseThrow();
                assertArrayEquals(new byte[] {1, 64}, now.items(), "A's diamonds survive");
                assertEquals(0, now.cost().signum());
                assertTrue(store.updateKit(opened.withVersion(5)).join().isEmpty());
                assertTrue(store.updateKit(new Kit("ghost", 0, false, false, BigDecimal.ZERO, new byte[0], null, "",
                        -1, 1)).join().isEmpty(), "a deleted kit cannot be resurrected by a save");
            } finally {
                db.close();
            }
        }
    }

    @Test
    void editorReturnsArePersistedAndTakenOnce(@TempDir Path folder) throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Database db = new Database(new CoreConfig.Database(), folder, executor, LoggerFactory.getLogger("kits-test"));
            try {
                db.migrate("kits", KitStore.MIGRATIONS);
                KitStore store = new KitStore(db);
                UUID admin = UUID.randomUUID();
                UUID other = UUID.randomUUID();
                store.addReturn(admin, new byte[] {1, 2}).join();
                store.addReturn(admin, new byte[] {3}).join();
                store.addReturn(other, new byte[] {9}).join();
                List<byte[]> taken = store.takeReturns(admin).join();
                assertEquals(2, taken.size());
                assertArrayEquals(new byte[] {1, 2}, taken.get(0));
                assertArrayEquals(new byte[] {3}, taken.get(1));
                assertTrue(store.takeReturns(admin).join().isEmpty(), "taken returns are gone, never given twice");
                assertEquals(1, store.takeReturns(other).join().size());
            } finally {
                db.close();
            }
        }
    }
}
