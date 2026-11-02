package dev.yanianz.atrium.module.placeholders;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A small thread-safe cache whose entries expire after a deadline.
 *
 * <p>All times are caller-supplied epoch milliseconds so the class stays independent of the clock and
 * unit-testable. A read of an expired entry also removes it.
 */
final class ExpiringCache<V> {
    private record Entry<V>(V value, long expiresAt) {}

    private final ConcurrentHashMap<UUID, Entry<V>> entries = new ConcurrentHashMap<>();

    void put(UUID id, V value, long expiresAt) {
        entries.put(id, new Entry<>(value, expiresAt));
    }

    /** The value while it has not expired, otherwise {@code null}. */
    V get(UUID id, long now) {
        Entry<V> entry = entries.get(id);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAt() <= now) {
            entries.remove(id, entry);
            return null;
        }
        return entry.value();
    }

    void remove(UUID id) {
        entries.remove(id);
    }

    void clear() {
        entries.clear();
    }

    int size() {
        return entries.size();
    }
}
