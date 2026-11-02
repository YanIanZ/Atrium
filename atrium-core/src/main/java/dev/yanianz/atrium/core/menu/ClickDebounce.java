package dev.yanianz.atrium.core.menu;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-player click rate limit. A click is accepted when at least {@code windowMillis} passed since the last
 * accepted click of the same player; rejected clicks do not extend the window. Thread-safe.
 */
public final class ClickDebounce {
    private final Map<UUID, Long> lastAccepted = new ConcurrentHashMap<>();
    private final LongSupplier clockMillis;

    public ClickDebounce() {
        this(() -> System.nanoTime() / 1_000_000L);
    }

    /** @param clockMillis monotonic milliseconds (injectable for tests) */
    public ClickDebounce(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    /** True if the click is accepted (and recorded); false if it came too soon. */
    public boolean tryClick(UUID player, long windowMillis) {
        if (windowMillis <= 0) {
            lastAccepted.put(player, clockMillis.getAsLong());
            return true;
        }
        long now = clockMillis.getAsLong();
        boolean[] accepted = {false};
        lastAccepted.compute(player, (id, last) -> {
            if (last == null || now - last >= windowMillis) {
                accepted[0] = true;
                return now;
            }
            return last;
        });
        return accepted[0];
    }

    public void forget(UUID player) {
        lastAccepted.remove(player);
    }

    int tracked() {
        return lastAccepted.size();
    }
}
