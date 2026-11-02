package dev.yanianz.atrium.api.teleport;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * {@link TeleportService} with per-call options and spawn lookup. Published by the teleport module under
 * both interfaces. Safe to call from any thread.
 *
 * <p>Failure messages (cooldown, moved, damaged, unsafe, failed) are sent to the player by the service;
 * callers only report success or offer follow-up actions.
 */
public interface ExtendedTeleportService extends TeleportService {
    CompletableFuture<TeleportResult> teleport(Player player, Location target, TeleportCause cause, TeleportOptions options);

    /**
     * The spawn players in {@code world} are sent to by {@code /spawn}: the world's own spawn when set,
     * else the default spawn, else the spawn point of the main world. Empty only when no world is loaded.
     */
    Optional<Location> spawnLocation(@Nullable World world);

    /** True while the player has a warmup running. */
    boolean hasPendingTeleport(Player player);
}
