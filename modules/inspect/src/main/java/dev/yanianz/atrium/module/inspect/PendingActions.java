package dev.yanianz.atrium.module.inspect;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Two-step confirmation for destructive commands: the first call records a {@code key}, a second call with
 * the same key inside the window returns {@code true} and consumes the record. Pure (the clock is injected)
 * so expiry and single-use behaviour are unit tested.
 */
final class PendingActions {
    private final LongSupplier clock;
    private final Map<String, Long> pending = new ConcurrentHashMap<>();

    PendingActions(LongSupplier clock) {
        this.clock = clock;
    }

    /** @return true when a matching request exists, is still valid and was consumed */
    boolean confirm(String key) {
        long now = clock.getAsLong();
        Long expires = pending.get(key);
        if (expires == null) {
            return false;
        }
        if (expires < now) {
            pending.remove(key, expires);
            return false;
        }
        return pending.remove(key, expires);
    }

    /** Records a request for the next call; the previous request of the same key is replaced. */
    void request(String key, long ttlMillis) {
        pending.put(key, clock.getAsLong() + Math.max(1L, ttlMillis));
    }

    /** Drops every pending request (module disable). */
    void clear() {
        pending.clear();
    }

    int size() {
        return pending.size();
    }
}
