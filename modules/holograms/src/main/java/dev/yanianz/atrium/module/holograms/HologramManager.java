package dev.yanianz.atrium.module.holograms;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Hologram definitions. Edits of one hologram run strictly one after another (a per-name future chain) and
 * read the latest stored value, so two admins editing at once cannot overwrite each other. A change becomes
 * visible (in memory and in the world) only after it was stored.
 */
final class HologramManager {
    enum Outcome { OK, NOT_FOUND, EXISTS, INVALID, NO_SUCH_LINE, NO_SUCH_PAGE, LIMIT, UNCHANGED }

    /** Result of an edit function: the outcome and, for {@link Outcome#OK}, the new value (null = delete). */
    record Change(Outcome outcome, @Nullable Hologram next) {
        static Change fail(Outcome outcome) {
            return new Change(outcome, null);
        }

        static Change ok(Hologram next) {
            return new Change(Outcome.OK, next);
        }

        static Change delete() {
            return new Change(Outcome.OK, null);
        }
    }

    interface Listener {
        /** {@code old} null = created, {@code next} null = deleted. */
        void changed(@Nullable Hologram old, @Nullable Hologram next);
    }

    private final HologramStorage storage;
    private final Listener listener;
    private final Map<String, Hologram> holograms = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<?>> chains = new ConcurrentHashMap<>();

    HologramManager(HologramStorage storage, Listener listener) {
        this.storage = storage;
        this.listener = listener;
    }

    CompletableFuture<List<Hologram>> load(Supplier<HologramSettings> defaults) {
        return storage.loadAll(defaults).thenApply(list -> {
            holograms.clear();
            list.forEach(h -> holograms.put(h.key(), h));
            return list;
        });
    }

    Optional<Hologram> get(String name) {
        return Optional.ofNullable(holograms.get(Hologram.key(name)));
    }

    Collection<Hologram> all() {
        return List.copyOf(holograms.values());
    }

    /**
     * Runs {@code edit} on the current value (null when missing) after every earlier edit of the same name
     * finished, stores the result and then publishes it. Storage failures complete exceptionally.
     */
    CompletableFuture<Outcome> edit(String name, Function<@Nullable Hologram, Change> edit) {
        String key = Hologram.key(name);
        CompletableFuture<Outcome> next;
        synchronized (chains) {
            CompletableFuture<?> previous = chains.getOrDefault(key, CompletableFuture.completedFuture(null));
            // An earlier failure must not block later edits: continue after it either way.
            next = previous.handle((ignored, error) -> null).thenCompose(ignored -> apply(key, edit));
            chains.put(key, next);
        }
        CompletableFuture<Outcome> tail = next;
        tail.whenComplete((outcome, error) -> {
            synchronized (chains) {
                chains.remove(key, tail);
            }
        });
        return tail;
    }

    private CompletableFuture<Outcome> apply(String key, Function<@Nullable Hologram, Change> edit) {
        Hologram current = holograms.get(key);
        Change change;
        try {
            change = edit.apply(current);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        if (change.outcome() != Outcome.OK) {
            return CompletableFuture.completedFuture(change.outcome());
        }
        Hologram next = change.next();
        if (next == null) {
            if (current == null) {
                return CompletableFuture.completedFuture(Outcome.NOT_FOUND);
            }
            return storage.delete(key).thenApply(v -> {
                holograms.remove(key);
                listener.changed(current, null);
                return Outcome.OK;
            });
        }
        if (!next.key().equals(key)) {
            return CompletableFuture.failedFuture(new IllegalStateException("edit changed the hologram name"));
        }
        if (next.equals(current)) {
            return CompletableFuture.completedFuture(Outcome.UNCHANGED);
        }
        return storage.save(next).thenApply(v -> {
            holograms.put(key, next);
            listener.changed(current, next);
            return Outcome.OK;
        });
    }
}
