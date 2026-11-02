package dev.yanianz.atrium.module.teleport;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the teleports this module started itself while their Bukkit teleport is in flight. The
 * {@code PlayerTeleportEvent} listener skips these, because {@link TeleportServiceImpl} records their
 * /back location itself once the teleport really happened. Without this, one teleport would be recorded
 * twice and /back would walk a history that is twice as short and one step stale.
 *
 * <p>Registration is reference-counted because two teleports of one player can overlap; an entry is
 * removed when its teleport future completes, when the player quits or when the module is disabled.
 */
final class StartedTeleports {
    private final Map<UUID, Integer> inFlight = new ConcurrentHashMap<>();

    /** Marks a teleport of {@code player} as started by this module. */
    void begin(UUID player) {
        inFlight.merge(player, 1, Integer::sum);
    }

    /** Unmarks one started teleport (the teleport finished, with or without success). */
    void end(UUID player) {
        inFlight.computeIfPresent(player, (key, count) -> count <= 1 ? null : count - 1);
    }

    /** True while at least one teleport of {@code player} started by this module is in flight. */
    boolean isStarted(UUID player) {
        return inFlight.containsKey(player);
    }

    void clear(UUID player) {
        inFlight.remove(player);
    }

    void clearAll() {
        inFlight.clear();
    }
}
