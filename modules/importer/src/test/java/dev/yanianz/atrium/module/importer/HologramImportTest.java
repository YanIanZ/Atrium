package dev.yanianz.atrium.module.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.holograms.HologramService;
import dev.yanianz.atrium.api.holograms.HologramService.HologramInfo;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * --overwrite must never delete the existing hologram before the replacement is known to be valid. A
 * rejected create or page keeps the old pages and location.
 */
class HologramImportTest {
    private static final StoredLocation OLD_LOCATION = new StoredLocation("world", 1, 64, 2, 0f, 0f);
    private static final StoredLocation NEW_LOCATION = new StoredLocation("world_nether", 9, 70, 9, 90f, 0f);

    private static final class FakeHolograms implements HologramService {
        final Map<String, HologramInfo> stored = new LinkedHashMap<>();
        int deletes;
        /** Number of the next create calls that must fail with a broken storage future. */
        int failNextCreate;
        /** Number of the next create calls that must be rejected as invalid. */
        int rejectNextCreate;
        int rejectPage = -1;
        int rejectPageTimes = 1;

        @Override
        public Collection<String> names() {
            return List.copyOf(stored.keySet());
        }

        @Override
        public Optional<HologramInfo> get(String name) {
            return Optional.ofNullable(stored.get(name));
        }

        @Override
        public CompletableFuture<Result> create(String name, StoredLocation location, List<String> lines) {
            if (failNextCreate > 0) {
                failNextCreate--;
                CompletableFuture<Result> failed = new CompletableFuture<>();
                failed.completeExceptionally(new IllegalStateException("storage unavailable"));
                return failed;
            }
            if (rejectNextCreate > 0) {
                rejectNextCreate--;
                return CompletableFuture.completedFuture(Result.INVALID);
            }
            if (stored.containsKey(name)) {
                return CompletableFuture.completedFuture(Result.EXISTS);
            }
            stored.put(name, new HologramInfo(name, location, List.of(List.copyOf(lines))));
            return CompletableFuture.completedFuture(Result.SUCCESS);
        }

        @Override
        public CompletableFuture<Result> delete(String name) {
            deletes++;
            return stored.remove(name) == null
                    ? CompletableFuture.completedFuture(Result.NOT_FOUND)
                    : CompletableFuture.completedFuture(Result.SUCCESS);
        }

        @Override
        public CompletableFuture<Result> addLine(String name, String line) {
            return CompletableFuture.completedFuture(Result.INVALID);
        }

        @Override
        public CompletableFuture<Result> setPage(String name, int page, List<String> lines) {
            if (page == rejectPage && rejectPageTimes > 0) {
                rejectPageTimes--;
                return CompletableFuture.completedFuture(Result.INVALID);
            }
            HologramInfo current = stored.get(name);
            if (current == null) {
                return CompletableFuture.completedFuture(Result.NOT_FOUND);
            }
            List<List<String>> pages = new ArrayList<>();
            current.pages().forEach(p -> pages.add(new ArrayList<>(p)));
            if (page == pages.size() + 1) {
                pages.add(new ArrayList<>(lines));
            } else if (page >= 1 && page <= pages.size()) {
                pages.set(page - 1, new ArrayList<>(lines));
            } else {
                return CompletableFuture.completedFuture(Result.INVALID);
            }
            stored.put(name, new HologramInfo(name, current.location(), pages));
            return CompletableFuture.completedFuture(Result.SUCCESS);
        }

        @Override
        public CompletableFuture<Result> move(String name, StoredLocation location) {
            HologramInfo current = stored.get(name);
            if (current == null) {
                return CompletableFuture.completedFuture(Result.NOT_FOUND);
            }
            stored.put(name, new HologramInfo(name, location, current.pages()));
            return CompletableFuture.completedFuture(Result.SUCCESS);
        }
    }

    private static final HologramImport.Waiter JOIN = new HologramImport.Waiter() {
        @Override
        public <T> T await(CompletableFuture<T> future, String what) {
            return future.join();
        }
    };

    private static FakeHolograms withOldHologram() {
        FakeHolograms service = new FakeHolograms();
        service.stored.put("shop", new HologramInfo("shop", OLD_LOCATION, List.of(List.of("old one"), List.of("old two"))));
        return service;
    }

    @Test
    void successfulOverwriteReplacesAllPages() {
        FakeHolograms service = withOldHologram();

        HologramImport.Outcome outcome = HologramImport.install(service, JOIN, "shop", NEW_LOCATION,
                List.of(List.of("new one"), List.of("new two")), true);

        assertTrue(outcome.success());
        HologramInfo stored = service.stored.get("shop");
        assertEquals(NEW_LOCATION, stored.location());
        assertEquals(List.of(List.of("new one"), List.of("new two")), stored.pages());
    }

    @Test
    void rejectedPageRestoresThePreviousHologram() {
        FakeHolograms service = withOldHologram();
        service.rejectPage = 2;

        HologramImport.Outcome outcome = HologramImport.install(service, JOIN, "shop", NEW_LOCATION,
                List.of(List.of("new one"), List.of("new two")), true);

        assertFalse(outcome.success());
        assertTrue(outcome.restored(), outcome.problem());
        HologramInfo stored = service.stored.get("shop");
        assertEquals(OLD_LOCATION, stored.location());
        assertEquals(List.of(List.of("old one"), List.of("old two")), stored.pages());
    }

    @Test
    void rejectedCreateRestoresThePreviousHologram() {
        FakeHolograms service = withOldHologram();
        service.rejectNextCreate = 1;

        HologramImport.Outcome outcome = HologramImport.install(service, JOIN, "shop", NEW_LOCATION,
                List.of(List.of("new one")), true);

        assertFalse(outcome.success());
        assertTrue(outcome.restored(), outcome.problem());
        assertEquals(List.of(List.of("old one"), List.of("old two")), service.stored.get("shop").pages());
        assertEquals(OLD_LOCATION, service.stored.get("shop").location());
    }

    @Test
    void failedCreateFutureRestoresThePreviousHologram() {
        FakeHolograms service = withOldHologram();
        service.failNextCreate = 1;

        HologramImport.Outcome outcome = HologramImport.install(service, JOIN, "shop", NEW_LOCATION,
                List.of(List.of("new one")), true);

        assertFalse(outcome.success());
        assertTrue(outcome.restored(), outcome.problem());
        assertTrue(outcome.problem().contains("storage unavailable"));
        assertEquals(List.of(List.of("old one"), List.of("old two")), service.stored.get("shop").pages());
    }

    @Test
    void invalidDataIsRejectedBeforeTheOldHologramIsDeleted() {
        FakeHolograms service = withOldHologram();

        HologramImport.Outcome empty = HologramImport.install(service, JOIN, "shop", NEW_LOCATION,
                List.of(), true);
        HologramImport.Outcome nullPage = HologramImport.install(service, JOIN, "shop", NEW_LOCATION,
                java.util.Arrays.asList((List<String>) null), true);

        assertFalse(empty.success());
        assertFalse(nullPage.success());
        assertEquals(0, service.deletes, "the existing hologram must not be touched");
        assertEquals(List.of(List.of("old one"), List.of("old two")), service.stored.get("shop").pages());
    }

    @Test
    void aNewHologramIsRemovedWhenALaterPageFails() {
        FakeHolograms service = new FakeHolograms();
        service.rejectPage = 2;

        HologramImport.Outcome outcome = HologramImport.install(service, JOIN, "shop", NEW_LOCATION,
                List.of(List.of("new one"), List.of("new two")), false);

        assertFalse(outcome.success());
        assertTrue(outcome.restored());
        assertFalse(service.stored.containsKey("shop"), "the partial hologram must not be left behind");
    }
}
