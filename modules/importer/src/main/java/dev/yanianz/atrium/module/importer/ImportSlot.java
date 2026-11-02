package dev.yanianz.atrium.module.importer;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Holds the current (or last) import job. A new job is claimed with compareAndSet against the job that
 * was seen finished, so two commands racing past the "already running?" check cannot both start one.
 */
final class ImportSlot<T> {
    private final AtomicReference<T> current = new AtomicReference<>();
    private final Predicate<T> finished;

    ImportSlot(Predicate<T> finished) {
        this.finished = finished;
    }

    T get() {
        return current.get();
    }

    /** Whether a job is running now (a fast pre-check; {@link #tryClaim} is the authoritative one). */
    boolean busy() {
        T seen = current.get();
        return seen != null && !finished.test(seen);
    }

    /**
     * Claims the slot for {@code job}. Fails when a job is running or another caller claimed the slot
     * between this caller's check and its claim.
     */
    boolean tryClaim(T job) {
        T seen = current.get();
        if (seen != null && !finished.test(seen)) {
            return false;
        }
        return current.compareAndSet(seen, job);
    }
}
