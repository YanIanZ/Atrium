package dev.yanianz.atrium.api.rtp;

import dev.yanianz.atrium.api.teleport.TeleportService.TeleportResult;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Random teleport search published by the rtp module. Safe to call from any thread; chunks are loaded
 * asynchronously and blocks are only read on the region that owns them.
 */
public interface RandomTeleportService {
    /**
     * Finds a safe random location in {@code world} using that world's RTP settings (area, biomes, avoided
     * blocks, world border). Completes empty when random teleport is disabled for the world or no spot was
     * found within the configured attempts. Never teleports anyone.
     */
    CompletableFuture<Optional<Location>> findLocation(World world);

    /**
     * Searches a location and teleports the player there right away: no warmup, cooldown or cost
     * (for plugins, first-join and respawn teleports). Completes with {@link TeleportResult#UNSAFE} when no
     * location was found.
     */
    CompletableFuture<TeleportResult> randomTeleport(Player player, World world);
}
