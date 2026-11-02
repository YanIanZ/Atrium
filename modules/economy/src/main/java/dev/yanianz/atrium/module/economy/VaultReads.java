package dev.yanianz.atrium.module.economy;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;

/**
 * Read policy for the Vault compatibility layer.
 *
 * <p>Vault is a synchronous API, so every read has to produce a value on the caller's thread. Atrium
 * keeps an online player's balance cached from before the player is in game, which answers the common
 * case without touching storage: scoreboard and placeholder plugins call Vault for online players from
 * their own threads at join, and waiting there stalls that plugin for up to the Vault timeout.
 *
 * <p>When the value is not cached, the fallbacks are ordered by thread safety:
 * <ul>
 *   <li>a regular plugin thread may wait a bounded amount of time and gets the real value;</li>
 *   <li>a tick thread never waits: a stalled region thread delays every entity in that region, so the
 *       read answers from the cache, or starts a background load and reports {@code 0} for this call;</li>
 *   <li>a read for an online player never waits either, because the warm path makes a cache miss a
 *       storage problem rather than a normal state.</li>
 * </ul>
 * The {@code 0} answer matches what the guarded Vault boundary already reports when a synchronous read
 * fails or times out, so a caller that cannot wait loses no correctness it had before. Callers that
 * need exact values for offline accounts must use {@code EconomyService} futures.
 */
final class VaultReads {
    /** How one read is answered. */
    enum Mode { CACHE, DEFER, WAIT }

    /** Pure decision table: cache first, then wait only where waiting cannot hurt the server. */
    static Mode decide(boolean cached, boolean online, boolean tickThread) {
        if (cached) return Mode.CACHE;
        if (online || tickThread) return Mode.DEFER;
        return Mode.WAIT;
    }

    /** The storage side this policy reads from, kept narrow so the policy is testable without a server. */
    interface Store {
        /** Newest known value: the committed balance of an online player, or a recent offline read. */
        BigDecimal cachedRead(UUID account);

        boolean isOnline(UUID account);

        CompletableFuture<BigDecimal> balance(UUID account);

        /** Keeps an asynchronously loaded value so the next non-waiting read can use it. */
        void rememberRead(UUID account, BigDecimal value);
    }

    private static final long WARNING_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final Store store;
    private final Logger logger;
    private final long timeoutMillis;
    private final BooleanSupplier tickThread;
    private final BooleanSupplier enabled;
    private final Set<UUID> loading = ConcurrentHashMap.newKeySet();
    private final RateLimited waits = new RateLimited();
    private final RateLimited deferred = new RateLimited();

    VaultReads(Store store, Logger logger, int timeoutMillis, BooleanSupplier tickThread, BooleanSupplier enabled) {
        this.store = store;
        this.logger = logger;
        this.timeoutMillis = timeoutMillis;
        this.tickThread = tickThread;
        this.enabled = enabled;
    }

    /** Whether the caller must not block, for example because it runs a region's tick loop. */
    boolean onTickThread() { return tickThread.getAsBoolean(); }

    /** Reads a balance without ever waiting on a tick thread or for an online player. */
    BigDecimal balance(UUID account) {
        BigDecimal cached = store.cachedRead(account);
        boolean online = store.isOnline(account);
        return switch (decide(cached != null, online, onTickThread())) {
            case CACHE -> cached;
            case DEFER -> {
                defer(account);
                yield BigDecimal.ZERO;
            }
            case WAIT -> await(store.balance(account));
        };
    }

    /** Loads a value in the background; concurrent callers share one load. */
    void defer(UUID account) {
        if (!loading.add(account)) return;
        warnDeferred(account.toString());
        store.balance(account).whenComplete((value, failure) -> {
            loading.remove(account);
            if (failure != null) {
                logger.warn("Deferred Vault balance read failed for {}", account, failure);
                return;
            }
            store.rememberRead(account, value);
        });
    }

    /** Rate-limited notice for a read that answered {@code 0} instead of waiting. */
    void warnDeferred(String what) {
        if (!deferred.warn()) return;
        logger.warn("{} deferred Vault read(s) answered 0 without waiting, latest for {} on thread '{}'; online balances "
                + "are cached and offline balances load in the background", deferred.reported(), what,
                Thread.currentThread().getName());
    }

    /**
     * Bounded synchronous wait for callers that are allowed to block. Only writes and offline lookups
     * from regular plugin threads reach this; a timeout leaves the write outcome unknown.
     */
    <T> T await(CompletableFuture<T> future) {
        if (!enabled.getAsBoolean()) throw new IllegalStateException("Economy disabled");
        if (!future.isDone() && waits.warn()) {
            // Rate-limited (once a minute, with a count) so a bulk caller such as /vault-convert cannot flood the log.
            logger.warn("{} synchronous Vault wait(s), latest on thread '{}' (limit {} ms). Online reads and tick threads "
                    + "never wait; prefer EconomyService futures.", waits.reported(), Thread.currentThread().getName(),
                    timeoutMillis);
        }
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Vault operation interrupted; write outcome may be unknown", e);
            throw new IllegalStateException("Interrupted; outcome unknown", e);
        } catch (ExecutionException | TimeoutException e) {
            logger.warn("Vault operation did not complete synchronously; do not blindly retry a timed-out write", e);
            throw new IllegalStateException("Storage error or timeout; outcome unknown", e);
        }
    }

    /** Counts suppressed calls so one log line per minute still reports how much was hidden. */
    private static final class RateLimited {
        private final AtomicLong last = new AtomicLong();
        private final AtomicLong count = new AtomicLong();

        boolean warn() {
            long now = System.nanoTime();
            long previous = last.get();
            count.incrementAndGet();
            if (previous != 0L && now - previous < WARNING_INTERVAL_NANOS) return false;
            return last.compareAndSet(previous, now);
        }

        long reported() { return Math.max(1L, count.getAndSet(0)); }
    }
}
