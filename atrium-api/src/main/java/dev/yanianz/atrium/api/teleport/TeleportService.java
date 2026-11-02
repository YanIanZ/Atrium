package dev.yanianz.atrium.api.teleport;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Teleports with warmup, cooldown, safety checks and /back history. Safe to call from any thread. */
public interface TeleportService {
    /**
     * Teleports after the configured warmup (skipped with the bypass permission). Completes with the
     * result once the player arrived, moved during warmup, or the teleport failed.
     */
    CompletableFuture<TeleportResult> teleport(Player player, Location target, TeleportCause cause);

    /** Teleports immediately, without warmup or cooldown. */
    CompletableFuture<TeleportResult> teleportNow(Player player, Location target, TeleportCause cause);

    /** Location the player can return to with /back. */
    Optional<Location> backLocation(Player player);

    enum TeleportCause { COMMAND, HOME, WARP, SPAWN, RANDOM, REQUEST, BACK, DEATH_BACK, PLUGIN }

    enum TeleportResult { SUCCESS, CANCELLED_MOVED, CANCELLED_DAMAGED, CANCELLED_EVENT, UNSAFE, ON_COOLDOWN, FAILED }
}
