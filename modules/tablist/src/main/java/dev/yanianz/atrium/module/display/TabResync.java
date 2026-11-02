package dev.yanianz.atrium.module.display;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Re-applies Atrium's overrides in the TAB plugin as soon as TAB (re)loads, instead of on the next timer cycle.
 *
 * <p>TAB loads players asynchronously after they join and rebuilds every player on {@code /tab reload}; each
 * time, every API override is gone and TAB draws its own configuration until Atrium hands its values over again.
 * TAB reports both through its event bus ({@code PlayerLoadEvent}, {@code TabLoadEvent}), on TAB's own threads.
 * This class records them and runs one coalesced {@link Batch} on the executor (Atrium's global thread), however
 * many events arrived in between. {@link #wake()} only asks for a run, for example because new texts are ready.
 *
 * <p>Thread-safe. A request that arrives while a batch runs schedules another run, so nothing is lost.
 */
public final class TabResync {
    /**
     * What changed since the previous run.
     *
     * @param all     TAB reloaded: every player lost their overrides
     * @param players players TAB (re)loaded since the previous run
     */
    public record Batch(boolean all, Set<UUID> players) {
        public Batch {
            players = Set.copyOf(players);
        }

        /** Whether this player lost the overrides Atrium set. */
        public boolean affects(UUID player) {
            return all || players.contains(player);
        }

        /** Nothing was reloaded: only new texts are waiting. */
        public boolean onlyWake() {
            return !all && players.isEmpty();
        }
    }

    private final Executor executor;
    private final Consumer<Batch> target;
    private final Set<UUID> players = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean all = new AtomicBoolean();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private volatile boolean closed;

    /**
     * @param executor runs the batch, normally on Atrium's global thread
     * @param target   applies one batch; called on the executor
     */
    public TabResync(Executor executor, Consumer<Batch> target) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.target = Objects.requireNonNull(target, "target");
    }

    /** TAB loaded (or reloaded) one player. Any thread. */
    public void playerLoaded(UUID player) {
        players.add(player);
        schedule();
    }

    /** TAB reloaded its configuration. Any thread. */
    public void reloaded() {
        all.set(true);
        schedule();
    }

    /** Asks for a run without invalidating anything. Any thread. */
    public void wake() {
        schedule();
    }

    /** No more runs; a run already scheduled does nothing. */
    public void close() {
        closed = true;
        players.clear();
        all.set(false);
    }

    public boolean closed() {
        return closed;
    }

    private void schedule() {
        if (closed || !scheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(this::run);
        } catch (RuntimeException rejected) {
            scheduled.set(false);
            throw rejected;
        }
    }

    private void run() {
        // Cleared before draining: a request that arrives from now on schedules the next run.
        scheduled.set(false);
        if (closed) {
            return;
        }
        boolean everyone = all.getAndSet(false);
        Set<UUID> drained = Set.copyOf(players);
        players.removeAll(drained);
        target.accept(new Batch(everyone, drained));
    }
}
