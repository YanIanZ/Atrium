package dev.yanianz.atrium.module.portals;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * In-memory portals plus the spatial index. Every change is written to storage first; memory and the index
 * only change once the write succeeded, so a failed save is never reported (or applied) as success.
 * Name reservations stop two admins from creating the same portal at the same time.
 */
final class PortalRegistry {
    enum Outcome { CREATED, DELETED, UPDATED, EXISTS, NOT_FOUND, OVERLAP }

    record Result(Outcome outcome, Optional<Portal> portal) {
        static Result of(Outcome outcome) {
            return new Result(outcome, Optional.empty());
        }
    }

    private final PortalStorage storage;
    private final Map<String, Portal> portals = new ConcurrentHashMap<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final Object lock = new Object();
    private volatile PortalIndex index = PortalIndex.EMPTY;

    PortalRegistry(PortalStorage storage) {
        this.storage = storage;
    }

    CompletableFuture<Integer> load() {
        return storage.loadAll().thenApply(list -> {
            synchronized (lock) {
                portals.clear();
                for (Portal portal : list) {
                    portals.put(portal.key(), portal);
                }
                rebuild();
                return portals.size();
            }
        });
    }

    PortalIndex index() {
        return index;
    }

    Optional<Portal> get(String name) {
        return Optional.ofNullable(portals.get(Portal.key(name)));
    }

    Collection<Portal> all() {
        return List.copyOf(portals.values());
    }

    List<String> names() {
        return portals.values().stream().map(Portal::name).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    /** First existing portal that overlaps the area (name order), if any. */
    Optional<Portal> overlapping(Cuboid area) {
        return portals.values().stream().filter(p -> p.area().overlaps(area))
                .min(java.util.Comparator.comparing(Portal::key));
    }

    CompletableFuture<Result> create(Portal portal, boolean allowOverlap) {
        String key = portal.key();
        synchronized (lock) {
            if (portals.containsKey(key) || !pending.add(key)) {
                return CompletableFuture.completedFuture(Result.of(Outcome.EXISTS));
            }
            if (!allowOverlap) {
                Optional<Portal> other = overlapping(portal.area());
                if (other.isPresent()) {
                    pending.remove(key);
                    return CompletableFuture.completedFuture(new Result(Outcome.OVERLAP, other));
                }
            }
        }
        return storage.insert(portal).handle((ignored, error) -> {
            synchronized (lock) {
                pending.remove(key);
                if (error == null) {
                    portals.put(key, portal);
                    rebuild();
                }
            }
            if (error != null) {
                throw new java.util.concurrent.CompletionException(error);
            }
            return new Result(Outcome.CREATED, Optional.of(portal));
        });
    }

    CompletableFuture<Result> delete(String name) {
        String key = Portal.key(name);
        if (!portals.containsKey(key)) {
            return CompletableFuture.completedFuture(Result.of(Outcome.NOT_FOUND));
        }
        return storage.delete(key).thenApply(deleted -> {
            Portal removed;
            synchronized (lock) {
                removed = portals.remove(key);
                rebuild();
            }
            return removed == null && !deleted ? Result.of(Outcome.NOT_FOUND) : new Result(Outcome.DELETED, Optional.ofNullable(removed));
        });
    }

    /**
     * Changes target, action or permission. The change is applied to the current value after the write, so
     * two admins editing different settings of the same portal do not undo each other.
     */
    CompletableFuture<Result> update(String name, UnaryOperator<Portal> change) {
        String key = Portal.key(name);
        Portal current = portals.get(key);
        if (current == null) {
            return CompletableFuture.completedFuture(Result.of(Outcome.NOT_FOUND));
        }
        Portal changed = change.apply(current);
        return storage.updateSettings(changed, current).thenApply(found -> {
            if (!found) {
                return Result.of(Outcome.NOT_FOUND);
            }
            Portal now;
            synchronized (lock) {
                now = portals.computeIfPresent(key, (k, p) -> change.apply(p));
                rebuild();
            }
            return now == null ? Result.of(Outcome.NOT_FOUND) : new Result(Outcome.UPDATED, Optional.of(now));
        });
    }

    private void rebuild() {
        index = PortalIndex.build(portals.values());
    }
}
