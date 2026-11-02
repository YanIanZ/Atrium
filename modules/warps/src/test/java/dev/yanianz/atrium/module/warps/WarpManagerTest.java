package dev.yanianz.atrium.module.warps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.api.warps.WarpCreateEvent;
import dev.yanianz.atrium.module.warps.WarpManager.Outcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class WarpManagerTest {
    /** In-memory storage that records every write, can be told to fail and can hold writes open. */
    private static final class FakeStorage implements WarpStorage {
        final Map<String, Warp> rows = new ConcurrentSkipListMap<>();
        final List<String> writes = new CopyOnWriteArrayList<>();
        boolean fail;
        /** When set, an upsert only finishes when this future completes. */
        CompletableFuture<Void> blocked;

        @Override
        public CompletableFuture<List<Warp>> loadAll() {
            return CompletableFuture.completedFuture(List.copyOf(rows.values()));
        }

        @Override
        public CompletableFuture<Void> upsert(Warp warp) {
            writes.add("upsert " + warp.key());
            if (fail) {
                return CompletableFuture.failedFuture(new IllegalStateException("db down"));
            }
            if (blocked != null) {
                return blocked.thenRun(() -> rows.put(warp.key(), warp));
            }
            rows.put(warp.key(), warp);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Boolean> delete(String key) {
            writes.add("delete " + key);
            if (fail) {
                return CompletableFuture.failedFuture(new IllegalStateException("db down"));
            }
            return CompletableFuture.completedFuture(rows.remove(key) != null);
        }

        @Override
        public CompletableFuture<Boolean> rename(String oldKey, Warp renamed) {
            writes.add("rename " + oldKey + " -> " + renamed.key());
            if (fail) {
                return CompletableFuture.failedFuture(new IllegalStateException("db down"));
            }
            if (rows.remove(oldKey) == null) {
                return CompletableFuture.completedFuture(false);
            }
            rows.put(renamed.key(), renamed);
            return CompletableFuture.completedFuture(true);
        }
    }

    private static final StoredLocation SPAWN = new StoredLocation("world", 0.5, 64, 0.5, 0, 0);
    private static final StoredLocation SHOP = new StoredLocation("world", 100.5, 70, -20.5, 90, 0);

    private final FakeStorage storage = new FakeStorage();
    private Predicate<WarpCreateEvent> createAllowed = e -> true;
    private final WarpManager manager = new WarpManager(storage, e -> createAllowed.test(e), e -> true);

    private Outcome join(CompletableFuture<Outcome> future) {
        return future.join();
    }

    @Test
    void cancelledRenameKeepsTheOriginalWarp() {
        assertEquals(Outcome.CREATED, join(manager.set(null, "Shop", "general", SHOP, null)));
        storage.writes.clear();
        List<WarpCreateEvent> seen = new ArrayList<>();
        createAllowed = e -> {
            seen.add(e);
            return false;
        };
        assertEquals(Outcome.CANCELLED, join(manager.rename(null, "shop", "market")));
        assertEquals(1, seen.size());
        assertEquals("market", seen.getFirst().getName());
        assertEquals("Shop", seen.getFirst().getRenamedFrom().orElseThrow());
        assertTrue(manager.get("shop").isPresent());
        assertFalse(manager.get("market").isPresent());
        assertEquals(List.of(), storage.writes, "nothing may be written when the event was cancelled");
        assertTrue(storage.rows.containsKey("shop"));
    }

    @Test
    void failedRenameWriteKeepsTheOriginalWarp() {
        join(manager.set(null, "Shop", "general", SHOP, null));
        storage.fail = true;
        CompletionException error = assertThrows(CompletionException.class, () -> join(manager.rename(null, "shop", "market")));
        assertEquals("db down", error.getCause().getMessage());
        assertTrue(manager.get("shop").isPresent());
        assertFalse(manager.get("market").isPresent());
    }

    @Test
    void renameMovesTheWarpWithItsData() {
        join(manager.set(null, "Shop", "trade", SHOP, null));
        assertEquals(Outcome.RENAMED, join(manager.rename(null, "SHOP", "Market")));
        Warp renamed = manager.get("market").orElseThrow();
        assertEquals("Market", renamed.name());
        assertEquals("trade", renamed.category());
        assertEquals(SHOP, renamed.location());
        assertFalse(manager.get("shop").isPresent());
        assertEquals(List.of("market"), List.copyOf(storage.rows.keySet()));
    }

    @Test
    void renameRefusesExistingAndInvalidNames() {
        join(manager.set(null, "a", "general", SHOP, null));
        join(manager.set(null, "b", "general", SPAWN, null));
        assertEquals(Outcome.EXISTS, join(manager.rename(null, "a", "B")));
        assertEquals(Outcome.INVALID_NAME, join(manager.rename(null, "a", "bad name")));
        assertEquals(Outcome.NOT_FOUND, join(manager.rename(null, "missing", "c")));
        assertEquals(Outcome.RENAMED, join(manager.rename(null, "a", "A")), "changing only the case is allowed");
        assertEquals("A", manager.get("a").orElseThrow().name());
    }

    @Test
    void renameCannotTakeANameThatIsBeingCreated() {
        join(manager.set(null, "home", "general", SPAWN, null));
        storage.blocked = new CompletableFuture<>();
        CompletableFuture<Outcome> creating = manager.set(null, "shop", "general", SHOP, null);
        assertEquals(Outcome.EXISTS, join(manager.rename(null, "home", "shop")), "the pending name is taken");
        storage.blocked.complete(null);
        assertEquals(Outcome.CREATED, join(creating));
        assertEquals(Outcome.EXISTS, join(manager.rename(null, "home", "shop")), "the name is taken now");
    }

    @Test
    void cancelledCreateChangesNothingAndIsNotReportedAsSuccess() {
        createAllowed = e -> false;
        assertEquals(Outcome.CANCELLED, join(manager.set(null, "spawn", "general", SPAWN, null)));
        assertFalse(manager.get("spawn").isPresent());
        assertTrue(storage.writes.isEmpty());
    }

    @Test
    void failedCreateIsNotStoredInMemory() {
        storage.fail = true;
        assertThrows(CompletionException.class, () -> join(manager.set(null, "spawn", "general", SPAWN, null)));
        assertFalse(manager.get("spawn").isPresent());
    }

    @Test
    void movingKeepsCreatorAndCreationTime() {
        join(manager.set(null, "spawn", "general", SPAWN, java.util.UUID.randomUUID()));
        Warp before = manager.get("spawn").orElseThrow();
        assertEquals(Outcome.MOVED, join(manager.set(null, "SPAWN", "hub", SHOP, null)));
        Warp after = manager.get("spawn").orElseThrow();
        assertEquals(before.creator(), after.creator());
        assertEquals(before.createdAt(), after.createdAt());
        assertEquals("hub", after.category());
        assertEquals(SHOP, after.location());
    }

    @Test
    void deleteHonoursEventAndStorage() {
        WarpManager guarded = new WarpManager(storage, e -> true, e -> false);
        join(guarded.set(null, "spawn", "general", SPAWN, null));
        assertEquals(Outcome.CANCELLED, join(guarded.delete(null, "spawn")));
        assertTrue(guarded.get("spawn").isPresent());
        manager.load().join();
        assertEquals(Outcome.DELETED, join(manager.delete(null, "Spawn")));
        assertFalse(manager.get("spawn").isPresent());
        assertEquals(Outcome.NOT_FOUND, join(manager.delete(null, "spawn")));
    }

    @Test
    void permissionNodeIsLowerCase() {
        Warp warp = new Warp("Shop", "general", SHOP, null, 0);
        assertEquals("atrium.warp.shop", warp.permission());
        assertTrue(warp.derivedPermission());
    }

    @Test
    void customPermissionSurvivesMoveAndRenameAndCanBeCleared() {
        assertEquals(Outcome.CREATED, join(manager.set(null, "shop", "trade", SHOP, "atrium.warp.market", null,
                Integer.MAX_VALUE)));
        assertEquals("atrium.warp.market", manager.get("shop").orElseThrow().permission());
        assertFalse(manager.get("shop").orElseThrow().derivedPermission());

        assertEquals(Outcome.RENAMED, join(manager.rename(null, "shop", "market")));
        assertEquals("atrium.warp.market", manager.get("market").orElseThrow().permission());
        assertEquals(Outcome.MOVED, join(manager.set(null, "market", "hub", SPAWN, null, Integer.MAX_VALUE)));
        assertEquals("atrium.warp.market", manager.get("market").orElseThrow().permission(),
                "moving keeps the stored permission");

        assertEquals(Outcome.MOVED, join(manager.setPermission(null, "market", "atrium.warp.staff")));
        assertEquals("atrium.warp.staff", manager.get("market").orElseThrow().permission());
        assertEquals(Outcome.MOVED, join(manager.setPermission(null, "market", "")));
        assertEquals("atrium.warp.market", manager.get("market").orElseThrow().permission());
        assertTrue(manager.get("market").orElseThrow().derivedPermission());
    }

    @Test
    void invalidPermissionIsRejectedWithoutWriting() {
        assertEquals(Outcome.CREATED, join(manager.set(null, "shop", "general", SHOP, null)));
        storage.writes.clear();
        assertEquals(Outcome.INVALID_PERMISSION, join(manager.setPermission(null, "shop", "has space")));
        assertEquals(Outcome.INVALID_PERMISSION,
                join(manager.set(null, "shop", "general", SPAWN, "bad node", null, Integer.MAX_VALUE)));
        assertTrue(storage.writes.isEmpty());
    }

    @Test
    void playerLimitOnlyAppliesToNewWarps() {
        java.util.UUID owner = java.util.UUID.randomUUID();
        assertEquals(Outcome.CREATED, join(manager.set(null, "one", "general", SPAWN, owner, 1)));
        assertEquals(Outcome.LIMIT_REACHED, join(manager.set(null, "two", "general", SPAWN, owner, 1)));
        assertFalse(manager.get("two").isPresent(), "a refused warp must not appear in memory");
        assertTrue(storage.writes.stream().noneMatch(write -> write.contains("two")), "nothing may be stored");
        assertEquals(1, manager.ownedBy(owner));

        // Moving the existing warp keeps it within the limit and is still allowed.
        assertEquals(Outcome.MOVED, join(manager.set(null, "ONE", "hub", SHOP, owner, 1)));
        assertEquals(SHOP, manager.get("one").orElseThrow().location());
        assertEquals(owner, manager.get("one").orElseThrow().creator());
    }

    @Test
    void limitIsPerOwnerAndUnlimitedBypassesIt() {
        java.util.UUID first = java.util.UUID.randomUUID();
        java.util.UUID second = java.util.UUID.randomUUID();
        assertEquals(Outcome.CREATED, join(manager.set(null, "first", "general", SPAWN, first, 1)));
        assertEquals(Outcome.LIMIT_REACHED, join(manager.set(null, "first-extra", "general", SPAWN, first, 1)));
        assertEquals(Outcome.CREATED, join(manager.set(null, "second", "general", SPAWN, second, 1)),
                "one owner reaching the limit must not block another owner");
        assertEquals(Outcome.CREATED, join(manager.set(null, "first-extra", "general", SPAWN, first, Integer.MAX_VALUE)),
                "atrium.warps.limit.unlimited is expressed as Integer.MAX_VALUE");
        assertEquals(2, manager.ownedBy(first));
        assertEquals(1, manager.ownedBy(second));
    }

    @Test
    void failedCreateReleasesTheLimitReservation() {
        java.util.UUID owner = java.util.UUID.randomUUID();
        storage.fail = true;
        assertThrows(CompletionException.class,
                () -> join(manager.set(null, "shop", "general", SPAWN, owner, 1)));
        storage.fail = false;
        assertEquals(Outcome.CREATED, join(manager.set(null, "shop", "general", SPAWN, owner, 1)),
                "a failed write must not consume the owner's only slot");
        assertEquals(1, manager.ownedBy(owner));
    }

    @Test
    void failedOrCancelledCreateReleasesTheName() {
        storage.fail = true;
        assertThrows(CompletionException.class, () -> join(manager.set(null, "shop", "general", SPAWN, null)));
        storage.fail = false;
        createAllowed = e -> false;
        assertEquals(Outcome.CANCELLED, join(manager.set(null, "shop", "general", SPAWN, null)));
        createAllowed = e -> true;
        assertEquals(Outcome.CREATED, join(manager.set(null, "shop", "general", SPAWN, null)),
                "neither a failure nor a cancelled event may keep the name reserved");
    }

    @Test
    void twoConcurrentCreatesOfTheSameNameCannotBothSucceed() {
        java.util.UUID first = java.util.UUID.randomUUID();
        java.util.UUID second = java.util.UUID.randomUUID();
        storage.blocked = new CompletableFuture<>();
        CompletableFuture<Outcome> creating = manager.set(null, "Shop", "general", SPAWN, first, 5);
        assertEquals(Outcome.EXISTS, join(manager.set(null, "shop", "hub", SHOP, second, 5)),
                "the second create must not overwrite the warp that is being created");
        assertFalse(creating.isDone(), "the first create is still writing");
        storage.blocked.complete(null);
        assertEquals(Outcome.CREATED, join(creating));
        assertEquals(SPAWN, manager.get("shop").orElseThrow().location());
        assertEquals(first, manager.get("shop").orElseThrow().creator());
        assertEquals(1, storage.writes.size(), "only the first create may write");
    }

    @Test
    void aLimitedPlayerCannotReplaceAnotherPlayersWarpThatAppearedMeanwhile() {
        java.util.UUID owner = java.util.UUID.randomUUID();
        java.util.UUID other = java.util.UUID.randomUUID();
        assertEquals(Outcome.CREATED, join(manager.set(null, "shop", "general", SPAWN, owner, 5)));
        // /setwarp checks ownership before calling the manager; the warp may have been created since.
        assertEquals(Outcome.EXISTS, join(manager.set(null, "shop", "hub", SHOP, other, 5)));
        assertEquals(SPAWN, manager.get("shop").orElseThrow().location());
        assertEquals(owner, manager.get("shop").orElseThrow().creator());
        assertEquals(Outcome.MOVED, join(manager.set(null, "shop", "hub", SHOP, owner, 5)),
                "the owner may still move their own warp");
    }

    @Test
    void aCreateInFlightCountsAgainstTheOwnerLimit() {
        java.util.UUID owner = java.util.UUID.randomUUID();
        storage.blocked = new CompletableFuture<>();
        CompletableFuture<Outcome> creating = manager.set(null, "one", "general", SPAWN, owner, 1);
        assertEquals(Outcome.LIMIT_REACHED, join(manager.set(null, "two", "general", SPAWN, owner, 1)));
        storage.blocked.complete(null);
        assertEquals(Outcome.CREATED, join(creating));
        assertEquals(Outcome.LIMIT_REACHED, join(manager.set(null, "three", "general", SPAWN, owner, 1)));
        assertEquals(1, manager.ownedBy(owner));
    }

    @Test
    void concurrentCreatesNeverExceedTheOwnerLimit() throws Exception {
        java.util.UUID owner = java.util.UUID.randomUUID();
        int limit = 3;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Outcome>> tasks = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                String name = "warp" + i;
                tasks.add(() -> join(manager.set(null, name, "general", SPAWN, owner, limit)));
            }
            int created = 0;
            for (Future<Outcome> result : pool.invokeAll(tasks)) {
                if (result.get() == Outcome.CREATED) {
                    created++;
                }
            }
            assertEquals(limit, created, "a stored warp and its pending slot must never both be missing");
            assertEquals(limit, manager.ownedBy(owner));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void importUsesTheSameNameRulesAndSkipsExistingUnlessOverwrite() {
        assertEquals(Outcome.INVALID_NAME, join(manager.importWarp(null, "bad name", "general", SPAWN, null, false)));
        assertEquals(Outcome.INVALID_NAME, join(manager.importWarp(null, "spawn", "bad category", SPAWN, null, false)));
        assertTrue(storage.writes.isEmpty(), "an invalid import must not write anything");

        java.util.UUID creator = java.util.UUID.randomUUID();
        assertEquals(Outcome.CREATED, join(manager.importWarp(null, "Spawn", "general", SPAWN, creator, false)));
        storage.writes.clear();

        assertEquals(Outcome.EXISTS, join(manager.importWarp(null, "spawn", "hub", SHOP, null, false)));
        assertTrue(storage.writes.isEmpty(), "an existing warp is left untouched without overwrite");
        assertEquals(SPAWN, manager.get("spawn").orElseThrow().location());
        assertEquals(creator, manager.get("spawn").orElseThrow().creator());

        assertEquals(Outcome.MOVED, join(manager.importWarp(null, "spawn", "hub", SHOP, null, true)));
        assertEquals(SHOP, manager.get("spawn").orElseThrow().location());
        assertEquals("hub", manager.get("spawn").orElseThrow().category());
        assertEquals(creator, manager.get("spawn").orElseThrow().creator(), "moving keeps the owner");
    }

    @Test
    void consoleWarpsDoNotCountAgainstAnyone() {
        java.util.UUID owner = java.util.UUID.randomUUID();
        assertEquals(Outcome.CREATED, join(manager.set(null, "console", "general", SPAWN, null, 0)));
        assertEquals(Outcome.LIMIT_REACHED, join(manager.set(null, "player", "general", SPAWN, owner, 0)));
        assertEquals(0, manager.ownedBy(owner));
    }
}
