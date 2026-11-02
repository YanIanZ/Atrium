package dev.yanianz.atrium.vault;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Lock-free cache of "the best registered provider that is acceptable". Candidates come best first; the first one
 * accepted by {@code accept} wins. {@link #invalidate()} drops the cached choice; a resolution that races with an
 * invalidation is discarded (compare-and-set on a fresh cell), so a stale provider is never re-cached.
 */
final class ProviderCache<T> {
    private record Cell<T>(T value, boolean valid) {
    }

    private final Supplier<? extends List<? extends T>> candidates;
    private final Predicate<Object> accept;
    private final RateLimitedLog log;
    private final String what;
    private final AtomicReference<Cell<T>> cell = new AtomicReference<>(new Cell<>(null, false));

    ProviderCache(Supplier<? extends List<? extends T>> candidates, Predicate<Object> accept, RateLimitedLog log, String what) {
        this.candidates = candidates;
        this.accept = accept;
        this.log = log;
        this.what = what;
    }

    T get() {
        Cell<T> current = cell.get();
        if (current.valid()) return current.value();
        T resolved = resolve();
        cell.compareAndSet(current, new Cell<>(resolved, true));
        return resolved;
    }

    void invalidate() {
        cell.set(new Cell<>(null, false));
    }

    private T resolve() {
        try {
            List<? extends T> list = candidates.get();
            if (list == null) return null;
            for (T candidate : list) {
                if (candidate != null && accept.test(candidate)) return candidate;
            }
        } catch (RuntimeException e) {
            if (log != null) log.log(Level.SEVERE, "resolve:" + what, "Cannot list the registered " + what + " providers", e);
        }
        return null;
    }
}
