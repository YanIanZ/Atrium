package dev.yanianz.atrium.module.sourbycraft;

import dev.yanianz.atrium.module.sourbycraft.OwnerDecision.Action;
import dev.yanianz.atrium.module.sourbycraft.OwnerDecision.Observation;
import dev.yanianz.atrium.module.sourbycraft.OwnerDecision.Pending;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Tracks which profile each player's in-memory profile-scoped data belongs to and reconciles it with the
 * active profile ({@link OwnerDecision}). No SourbyCraft types: the hook feeds it restores, captures and
 * observations, so it is unit tested with fakes.
 *
 * <p>Threading: every method that may change data ({@link #beforeCapture}, {@link #restored},
 * {@link #observe}, {@link #reconcile}) runs on the player's owning entity thread, like SourbyCraft's own
 * capture/apply; one player's calls are additionally serialised on that player's entry.
 *
 * <p>Persistence: the owner is stored through {@link OwnerStore} (one row per account) only after the data
 * change it describes was stored, so a crash leaves at worst an old owner next to new data, which the next
 * reconciliation clears again, never a new owner next to another profile's data.
 */
final class ProfileOwnership {
    /** Where the owner is persisted (Atrium user component). */
    interface OwnerStore {
        /** Persisted owner of a loaded player, or null when unknown or not loaded. */
        @Nullable UUID load(UUID player);

        /** Persists the owner (null = unknown). */
        void save(UUID player, @Nullable UUID owner);
    }

    private static final class Entry {
        @Nullable UUID owner;
        @Nullable Pending pending;
        @Nullable Observation latest;
        long generation;

        Entry(@Nullable UUID owner) {
            this.owner = owner;
        }
    }

    private final ScopedStateBridge bridge;
    private final OwnerStore store;
    private final Supplier<ProfileScopes.MissingState> missing;
    private final LongSupplier clock;
    private final long settleMillis;
    private final Logger logger;
    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();

    ProfileOwnership(ScopedStateBridge bridge, OwnerStore store, Supplier<ProfileScopes.MissingState> missing,
            LongSupplier clock, long settleMillis, Logger logger) {
        this.bridge = bridge;
        this.store = store;
        this.missing = missing;
        this.clock = clock;
        this.settleMillis = settleMillis;
        this.logger = logger;
    }

    long now() {
        return clock.getAsLong();
    }

    private Entry entry(UUID player) {
        return entries.computeIfAbsent(player, id -> new Entry(store.load(id)));
    }

    /**
     * Before SourbyCraft captures: reconciles with the latest observation (so data of another profile is never
     * written into the profile being saved) and returns the owner to record in the captured data.
     */
    @Nullable UUID beforeCapture(UUID player) {
        Entry entry = entry(player);
        synchronized (entry) {
            reconcileLocked(player, entry);
            return entry.owner;
        }
    }

    /** SourbyCraft restored data (apply, or applyMissing on the extended API). */
    void restored(UUID player, ScopedStateBridge.Restored restored) {
        Entry entry = entry(player);
        synchronized (entry) {
            entry.pending = new Pending(now(), restored.owner(), entry.owner);
            entry.owner = restored.owner();
            persistAfter(player, entry, restored.stored());
        }
    }

    /**
     * Records what the profile system reported (lookup started at {@code startedAtMillis}) and reconciles.
     * Observations older than the latest one are ignored.
     */
    Action observe(UUID player, @Nullable UUID active, long startedAtMillis) {
        Entry entry = entry(player);
        synchronized (entry) {
            Observation latest = entry.latest;
            if (latest == null || startedAtMillis >= latest.startedAtMillis()) {
                entry.latest = new Observation(active, startedAtMillis);
            }
            return reconcileLocked(player, entry);
        }
    }

    /** Reconciles with the latest observation (lazily, before a command); NONE without one. */
    Action reconcile(UUID player) {
        Entry entry = entry(player);
        synchronized (entry) {
            return reconcileLocked(player, entry);
        }
    }

    private Action reconcileLocked(UUID player, Entry entry) {
        Observation latest = entry.latest;
        if (latest == null) {
            return Action.NONE;
        }
        Action action = OwnerDecision.decide(entry.owner, entry.pending, latest, missing.get(), settleMillis);
        UUID active = latest.active();
        switch (action) {
            case NONE, WAIT -> {
            }
            case CONFIRM -> entry.pending = null;
            case ADOPT -> {
                UUID previous = entry.owner;
                entry.owner = active;
                entry.pending = null;
                if (previous != null && !previous.equals(active)) {
                    logger.info("Profile-scoped Atrium data of {} now belongs to profile {} (kept, was {})",
                            player, active, previous);
                }
                persistAfter(player, entry, CompletableFuture.completedFuture(null));
            }
            case CLEAR -> {
                CompletableFuture<Void> stored;
                try {
                    stored = bridge.clear(player);
                } catch (Exception e) {
                    // Owner unchanged: the next reconciliation tries again; nothing is attributed to the new profile.
                    logger.error("Clearing the profile-scoped Atrium data of {} for profile {} failed", player, active, e);
                    return action;
                }
                logger.info("Profile of {} is now {}, which has no Atrium data of its own: profile-scoped data starts "
                        + "empty (it belonged to {})", player, active, entry.owner);
                entry.owner = active;
                entry.pending = null;
                persistAfter(player, entry, stored);
            }
        }
        return action;
    }

    /** Persists the entry's current owner once {@code stored} completed, unless a newer change superseded it. */
    private void persistAfter(UUID player, Entry entry, CompletableFuture<Void> stored) {
        long generation = ++entry.generation;
        UUID owner = entry.owner;
        stored.whenComplete((ignored, error) -> {
            if (error != null) {
                logger.warn("Profile-scoped data of {} was not stored; its owner stays unchanged in storage", player);
                return;
            }
            synchronized (entry) {
                if (entry.generation != generation) {
                    return;
                }
                try {
                    store.save(player, owner); // non-blocking: in-memory update plus an asynchronous write
                } catch (RuntimeException e) {
                    logger.error("Storing the profile owner of {} failed", player, e);
                }
            }
        });
    }

    /** The owner currently assumed for a player (tests, diagnostics). */
    @Nullable UUID owner(UUID player) {
        Entry entry = entries.get(player);
        return entry == null ? store.load(player) : entry.owner;
    }

    /** Whether an unconfirmed restore is pending (tests, diagnostics). */
    boolean pending(UUID player) {
        Entry entry = entries.get(player);
        return entry != null && entry.pending != null;
    }

    /** Time the latest observation started, or -1 (to decide whether to look again). */
    long latestObservation(UUID player) {
        Entry entry = entries.get(player);
        Observation latest = entry == null ? null : entry.latest;
        return latest == null ? -1 : latest.startedAtMillis();
    }

    /** Drops the in-memory entry (the persisted owner stays). */
    void forget(UUID player) {
        entries.remove(player);
    }

    void clear() {
        entries.clear();
    }
}
