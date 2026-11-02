package dev.yanianz.atrium.module.importer;

import dev.yanianz.atrium.api.holograms.HologramService;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Creates or replaces one imported hologram without ever discarding the previous one before the new
 * pages are complete.
 *
 * <p>The previous hologram is snapshotted (pages and location) before it is removed. If the creation or
 * any page is rejected, the partial replacement is removed and the snapshot is written back, so a failed
 * import leaves the server exactly as it was instead of deleting the original.
 */
final class HologramImport {
    /** Waits for a service write; must not give up while the write is still in flight. */
    interface Waiter {
        <T> T await(CompletableFuture<T> future, String what);
    }

    /**
     * @param success  true when the hologram holds every requested page
     * @param problem  a user-facing reason when {@code success} is false
     * @param restored whether the previous hologram (if any) is back in place after a failure
     * @param cause    the underlying exception, when there was one
     */
    record Outcome(boolean success, String problem, boolean restored, Throwable cause) {}

    private HologramImport() {}

    static Outcome install(HologramService service, Waiter waiter, String name, StoredLocation location,
            List<List<String>> pages, boolean replaceExisting) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(waiter, "waiter");
        // Validate the whole incoming record before the existing hologram is touched. Empty pages and a
        // missing location can never be stored, so they must fail while the old hologram still exists.
        if (name == null || name.isBlank() || location == null || pages == null || pages.isEmpty()
                || pages.stream().anyMatch(Objects::isNull)) {
            return new Outcome(false, "hologram data is incomplete", false, null);
        }
        List<List<String>> incoming = pages.stream().map(List::copyOf).toList();

        HologramService.HologramInfo previous = null;
        if (replaceExisting) {
            previous = service.get(name).orElse(null);
            if (previous == null) {
                return new Outcome(false, "the existing hologram disappeared before it was replaced", false, null);
            }
            HologramService.Result removed = waiter.await(service.delete(name), "removing hologram " + name);
            if (removed != HologramService.Result.SUCCESS && removed != HologramService.Result.NOT_FOUND) {
                return new Outcome(false, "existing hologram was not replaced: " + removed, false, null);
            }
        }

        boolean created = false;
        RuntimeException failure = null;
        try {
            HologramService.Result result = waiter.await(service.create(name, location, incoming.getFirst()),
                    "creating hologram " + name);
            if (result != HologramService.Result.SUCCESS) {
                throw new IllegalStateException("hologram was not created: " + result);
            }
            created = true;
            for (int page = 1; page < incoming.size(); page++) {
                HologramService.Result stored = waiter.await(service.setPage(name, page + 1, incoming.get(page)),
                        "storing page " + (page + 1) + " of hologram " + name);
                if (stored != HologramService.Result.SUCCESS) {
                    throw new IllegalStateException("page " + (page + 1) + " was not stored: " + stored);
                }
            }
        } catch (RuntimeException e) {
            failure = e;
        }
        if (failure == null) {
            return new Outcome(true, null, false, null);
        }

        StringBuilder problem = new StringBuilder(describe(failure));
        if (created) {
            try {
                HologramService.Result leftover = waiter.await(service.delete(name),
                        "removing the partial hologram " + name);
                if (leftover != HologramService.Result.SUCCESS && leftover != HologramService.Result.NOT_FOUND) {
                    problem.append("; the partial hologram could not be removed: ").append(leftover);
                }
            } catch (RuntimeException e) {
                problem.append("; the partial hologram could not be removed: ").append(describe(e));
            }
        }
        boolean restored = previous == null;
        if (previous != null) {
            try {
                restore(service, waiter, previous);
                restored = true;
            } catch (RuntimeException e) {
                problem.append("; restoring the previous hologram failed: ").append(describe(e));
            }
        }
        return new Outcome(false, problem.toString(), restored, failure);
    }

    private static void restore(HologramService service, Waiter waiter, HologramService.HologramInfo previous) {
        List<List<String>> pages = previous.pages();
        if (pages.isEmpty()) {
            throw new IllegalStateException("the previous hologram had no pages to restore");
        }
        HologramService.Result created = waiter.await(
                service.create(previous.name(), previous.location(), pages.getFirst()),
                "restoring hologram " + previous.name());
        if (created != HologramService.Result.SUCCESS) {
            throw new IllegalStateException("hologram was not restored: " + created);
        }
        for (int page = 1; page < pages.size(); page++) {
            HologramService.Result stored = waiter.await(
                    service.setPage(previous.name(), page + 1, pages.get(page)),
                    "restoring page " + (page + 1) + " of hologram " + previous.name());
            if (stored != HologramService.Result.SUCCESS) {
                throw new IllegalStateException("page " + (page + 1) + " was not restored: " + stored);
            }
        }
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
