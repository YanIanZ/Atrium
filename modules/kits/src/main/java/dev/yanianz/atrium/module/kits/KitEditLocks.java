package dev.yanianz.atrium.module.kits;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One editor per kit. An owner (an editor session) holds the lock of a kit until it releases it; a second owner
 * is refused and told who holds it. Releasing only works for the current owner, so a stale session can never
 * free the lock of a newer one.
 */
final class KitEditLocks<T> {
    private final Map<String, T> owners = new ConcurrentHashMap<>();

    /** Takes the lock; empty on success (or when {@code owner} already holds it), otherwise the current holder. */
    Optional<T> tryAcquire(String kit, T owner) {
        T holder = owners.putIfAbsent(kit, owner);
        return holder == null || holder == owner ? Optional.empty() : Optional.of(holder);
    }

    /** Releases the lock if {@code owner} holds it. */
    boolean release(String kit, T owner) {
        return owners.remove(kit, owner);
    }

    Optional<T> holder(String kit) {
        return Optional.ofNullable(owners.get(kit));
    }

    void clear() {
        owners.clear();
    }
}
