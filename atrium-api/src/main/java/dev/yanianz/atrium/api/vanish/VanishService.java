package dev.yanianz.atrium.api.vanish;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Vanish state of players. Published by the {@code vanish} module. Safe to call from any thread.
 *
 * <p>Vanished players have a level ({@code atrium.vanish.level.<n>}, default 1). A viewer sees a vanished
 * player when the viewer has {@code atrium.vanish.see} and their see level ({@code atrium.vanish.see.<n>},
 * default 1) is at least the vanished player's level. Permissions are read on every call, never cached.
 */
public interface VanishService {
    /** Whether the player is vanished. Online players are answered from memory; loaded offline users from their data. */
    boolean isVanished(UUID player);

    /**
     * Whether {@code viewer} may see {@code target}: always true for the same player and for players who are
     * not vanished. Chat, teleport, tab completion and list features should use this to hide vanished players.
     */
    boolean canSee(Player viewer, Player target);

    /**
     * Changes the vanish state on the player's own thread. A {@link VanishStateChangeEvent} carrying the new state
     * is fired first; nothing changes when it is cancelled. Completes with {@code true} when the state changed,
     * {@code false} when it already had that value, the event was cancelled or the player left.
     */
    CompletableFuture<Boolean> setVanished(Player player, boolean vanished, @Nullable CommandSender actor);

    default CompletableFuture<Boolean> setVanished(Player player, boolean vanished) {
        return setVanished(player, vanished, null);
    }
}
