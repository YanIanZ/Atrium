package dev.yanianz.atrium.module.integrations;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/** One isolated third-party protection hook. Implementations are only loaded when their plugin is enabled. */
interface ProtectionHook {
    String id();

    String displayName();

    String version();

    boolean canBuild(Player player, Location location);

    boolean canBreak(Player player, Block block);

    boolean canInteract(Player player, Location location);

    boolean canUseCommandAt(Player player, Location location, String command);
}
