package dev.yanianz.atrium.module.integrations;

import me.angeschossen.lands.api.LandsIntegration;
import me.angeschossen.lands.api.flags.type.Flags;
import me.angeschossen.lands.api.flags.type.RoleFlag;
import me.angeschossen.lands.api.land.Area;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Lands API hook. Areas and role flags are read on the caller's region thread; Lands resolves the area
 * from its loaded chunk data, so the chunk must already be loaded (Atrium never loads one just to check).
 */
final class LandsHook implements ProtectionHook {
    private final LandsIntegration lands;

    LandsHook(Plugin plugin) {
        this.lands = LandsIntegration.of(plugin);
        if (lands == null) {
            throw new IllegalStateException("Lands integration is not available");
        }
    }

    @Override
    public String id() {
        return "lands";
    }

    @Override
    public String displayName() {
        return "Lands";
    }

    @Override
    public String version() {
        var plugin = Bukkit.getPluginManager().getPlugin("Lands");
        return plugin == null ? "?" : plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean canBuild(Player player, Location location) {
        Area area = lands.getArea(location);
        return area == null || area.hasRoleFlag(player, Flags.BLOCK_PLACE, material(location), false);
    }

    @Override
    public boolean canBreak(Player player, Block block) {
        Area area = lands.getArea(block.getLocation());
        return area == null || area.hasRoleFlag(player, Flags.BLOCK_BREAK, block.getType(), false);
    }

    @Override
    public boolean canInteract(Player player, Location location) {
        Area area = lands.getArea(location);
        if (area == null) {
            return true;
        }
        Block block = location.getBlock();
        RoleFlag flag = Flags.getInteract(block);
        if (flag == null) {
            flag = Flags.INTERACT_GENERAL;
        }
        return area.hasRoleFlag(player, flag, block.getType(), false);
    }

    @Override
    public boolean canUseCommandAt(Player player, Location location, String command) {
        // Lands does not restrict commands by area.
        return true;
    }

    private static Material material(Location location) {
        return location.getBlock().getType();
    }
}
