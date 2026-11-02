package dev.yanianz.atrium.module.display;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Per-player cache for values that are only available asynchronously (rank, playtime, balance of a player
 * whose balance is not cached). Rendering never waits: it reads the last value and, when that value is
 * older than the refresh interval, starts one background refresh. Thread-safe.
 */
final class AsyncValueCache<V> {
    private record Entry<V>(V value, long fetchedAtNanos, boolean loading) {}

    private final String label;
    private final Logger logger;
    private final long refreshNanos;
    private final LongSupplier clock;
    private final ConcurrentHashMap<UUID, Entry<V>> entries = new ConcurrentHashMap<>();
    /** Logs the first failure in full; repeats are logged at debug until a refresh succeeds again. */
    private final AtomicBoolean failing = new AtomicBoolean();

    AsyncValueCache(String label, Logger logger, long refreshNanos, LongSupplier clock) {
        this.label = label;
        this.logger = logger;
        this.refreshNanos = Math.max(1, refreshNanos);
        this.clock = clock;
    }

    /** Last known value (may be {@code null} while the first load runs); refreshes in the background when stale. */
    V get(UUID id, Supplier<CompletableFuture<V>> loader) {
        long now = clock.getAsLong();
        boolean[] start = {false};
        Entry<V> current = entries.compute(id, (key, old) -> {
            if (old == null) {
                start[0] = true;
                return new Entry<>(null, now, true);
            }
            if (!old.loading() && now - old.fetchedAtNanos() >= refreshNanos) {
                start[0] = true;
                return new Entry<>(old.value(), old.fetchedAtNanos(), true);
            }
            return old;
        });
        if (start[0]) {
            load(id, loader);
            Entry<V> after = entries.get(id);
            return after == null ? current.value() : after.value();
        }
        return current.value();
    }

    private void load(UUID id, Supplier<CompletableFuture<V>> loader) {
        CompletableFuture<V> future;
        try {
            future = loader.get();
        } catch (RuntimeException e) {
            future = CompletableFuture.failedFuture(e);
        }
        future.whenComplete((value, error) -> {
            long now = clock.getAsLong();
            entries.computeIfPresent(id, (key, old) -> error == null
                    ? new Entry<>(value, now, false)
                    // Keep the previous value; retry after the normal refresh interval.
                    : new Entry<>(old.value(), now, false));
            if (error != null) {
                if (failing.compareAndSet(false, true)) {
                    logger.warn("Loading the {} placeholder for {} failed; showing the last known value", label, id, error);
                } else {
                    logger.debug("Loading the {} placeholder for {} failed again", label, id, error);
                }
            } else {
                failing.set(false);
            }
        });
    }

    void forget(UUID id) {
        entries.remove(id);
    }

    void clear() {
        entries.clear();
    }
}
