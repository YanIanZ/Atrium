package dev.yanianz.atrium.module.integrations;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * GriefPrevention hook. Claims are queried with the data store's synchronized lookup; call on the
 * region thread that owns the location. GriefPrevention does not advertise Folia support, so this hook
 * only becomes active on servers where the plugin itself is enabled.
 */
final class GriefPreventionHook implements ProtectionHook {
    @Override
    public String id() {
        return "griefprevention";
    }

    @Override
    public String displayName() {
        return "GriefPrevention";
    }

    @Override
    public String version() {
        var plugin = Bukkit.getPluginManager().getPlugin("GriefPrevention");
        return plugin == null ? "?" : plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean canBuild(Player player, Location location) {
        Claim claim = claim(location);
        return claim == null || claim.allowBuild(player, location.getBlock().getType()) == null;
    }

    @Override
    public boolean canBreak(Player player, Block block) {
        Claim claim = claim(block.getLocation());
        return claim == null || claim.allowBreak(player, block.getType()) == null;
    }

    @Override
    public boolean canInteract(Player player, Location location) {
        Claim claim = claim(location);
        return claim == null || (claim.allowAccess(player) == null && claim.allowContainers(player) == null);
    }

    @Override
    public boolean canUseCommandAt(Player player, Location location, String command) {
        // GriefPrevention does not restrict commands by claim.
        return true;
    }

    private static Claim claim(Location location) {
        GriefPrevention instance = GriefPrevention.instance;
        if (instance == null || instance.dataStore == null) {
            return null;
        }
        return instance.dataStore.getClaimAt(location, false, null);
    }
}
