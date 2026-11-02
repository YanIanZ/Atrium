package dev.yanianz.atrium.module.integrations;

import com.palmergames.bukkit.towny.object.TownyPermission;
import com.palmergames.bukkit.towny.utils.PlayerCacheUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Towny hook. {@link PlayerCacheUtil} reads and updates Towny's per-player cache; call on the region
 * thread that owns the location, never asynchronously.
 */
final class TownyHook implements ProtectionHook {
    @Override
    public String id() {
        return "towny";
    }

    @Override
    public String displayName() {
        return "Towny";
    }

    @Override
    public String version() {
        var plugin = Bukkit.getPluginManager().getPlugin("Towny");
        return plugin == null ? "?" : plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean canBuild(Player player, Location location) {
        return PlayerCacheUtil.getCachePermission(player, location, material(location), TownyPermission.ActionType.BUILD);
    }

    @Override
    public boolean canBreak(Player player, Block block) {
        return PlayerCacheUtil.getCachePermission(player, block.getLocation(), block.getType(),
                TownyPermission.ActionType.DESTROY);
    }

    @Override
    public boolean canInteract(Player player, Location location) {
        return PlayerCacheUtil.getCachePermission(player, location, material(location), TownyPermission.ActionType.SWITCH)
                && PlayerCacheUtil.getCachePermission(player, location, material(location),
                        TownyPermission.ActionType.ITEM_USE);
    }

    @Override
    public boolean canUseCommandAt(Player player, Location location, String command) {
        // Towny does not restrict commands by town block.
        return true;
    }

    private static Material material(Location location) {
        return location.getBlock().getType();
    }
}
