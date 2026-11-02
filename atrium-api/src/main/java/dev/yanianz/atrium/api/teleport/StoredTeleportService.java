package dev.yanianz.atrium.api.teleport;

import dev.yanianz.atrium.api.teleport.TeleportService.TeleportCause;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportResult;
import dev.yanianz.atrium.api.world.WorldResolver;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;

/**
 * Teleports to a {@link StoredLocation} whose world may not be loaded: the world is found through the
 * {@link WorldResolver} service (on-demand loading on SourbyCraft AWF) before the normal teleport pipeline
 * runs. Published by the teleport module. Safe to call from any thread.
 *
 * <p>When the world is not available the player gets a message with the reason and the result is
 * {@link TeleportResult#FAILED} with the {@link WorldResolver.Status}.
 */
public interface StoredTeleportService {
    record StoredTeleportResult(TeleportResult result, WorldResolver.Status worldStatus) {
        public boolean success() {
            return result == TeleportResult.SUCCESS;
        }
    }

    CompletableFuture<StoredTeleportResult> teleport(Player player, StoredLocation target, TeleportCause cause,
            TeleportOptions options);
}
