package dev.yanianz.atrium.api.protection;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Optional bridge to the claim/region plugins running on this server.
 *
 * <p>Every method returns {@code true} when no protection plugin is installed. When at least one is
 * present, the composite implementation asks every enabled hook and only allows the action when all of
 * them allow it. Players with {@code atrium.protection.bypass} skip the checks entirely.
 *
 * <p><b>Threading:</b> call these methods on the region thread that owns the location
 * ({@code scheduler().supplyAt(location, ...)} or {@code scheduler().entity(player, ...)} when the
 * location is the player's own position). The third-party claim APIs are not documented as
 * thread-safe on Folia, so never call them from an async, I/O or arbitrary global thread.
 */
public interface ProtectionService {
    /** Whether {@code player} may place or change a block at {@code location}. */
    boolean canBuild(Player player, Location location);

    /** Whether {@code player} may break {@code block}. */
    boolean canBreak(Player player, Block block);

    /** Whether {@code player} may interact with the block or entity at {@code location}. */
    boolean canInteract(Player player, Location location);

    /**
     * Whether {@code player} may run {@code command} at {@code location}. {@code command} is the
     * command without its leading slash (for example {@code sethome}).
     */
    boolean canUseCommandAt(Player player, Location location, String command);
}
