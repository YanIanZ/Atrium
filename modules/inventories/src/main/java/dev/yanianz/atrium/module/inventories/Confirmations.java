package dev.yanianz.atrium.module.inventories;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two-step confirmation for destructive commands. Pure and clock-injected, so the flow is unit-testable.
 *
 * <p>The first call records the request; only an identical request (same key) within the time-to-live is
 * accepted. Accepting removes the pending request, so a third identical call asks again instead of silently
 * repeating the action.
 */
final class Confirmations {
    private record Pending(String key, long expiresAt) {}

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    /**
     * @return {@code true} when {@code key} is the request that was recorded before it expired
     */
    boolean confirm(String actor, String key, long now, long ttlMillis) {
        if (actor == null || key == null || ttlMillis <= 0) {
            return false;
        }
        Pending current = pending.get(actor);
        if (current != null && current.key().equals(key) && now < current.expiresAt()) {
            pending.remove(actor, current);
            return true;
        }
        pending.put(actor, new Pending(key, now + ttlMillis));
        return false;
    }

    void clear(String actor) {
        if (actor != null) {
            pending.remove(actor);
        }
    }

    void clearIf(String actor, String key) {
        if (actor == null || key == null) {
            return;
        }
        Pending current = pending.get(actor);
        if (current != null && current.key().equals(key)) {
            pending.remove(actor, current);
        }
    }

    void clearAll() {
        pending.clear();
    }

    int size() {
        return pending.size();
    }
}
