package dev.yanianz.atrium.module.worlds;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Pending {@code /worlds unload} confirmations: one per sender, for one world, valid for a limited time. */
final class UnloadConfirmations {
    private record Pending(String world, long expiresAt) {}

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    UnloadConfirmations(LongSupplier clock) {
        this.clock = clock;
    }

    /** Asks the sender to confirm unloading the world within {@code validMillis}; replaces an older request. */
    void request(String sender, String world, long validMillis) {
        pending.put(sender, new Pending(world, clock.getAsLong() + Math.max(1, validMillis)));
    }

    /** True (and forgotten) when the sender asked to unload exactly this world and the request is still valid. */
    boolean consume(String sender, String world) {
        Pending entry = pending.remove(sender);
        return entry != null && entry.world().equalsIgnoreCase(world) && clock.getAsLong() <= entry.expiresAt();
    }

    void clear() {
        pending.clear();
    }
}
