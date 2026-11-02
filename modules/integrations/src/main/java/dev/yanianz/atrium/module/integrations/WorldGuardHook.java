package dev.yanianz.atrium.module.integrations;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import java.util.Set;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * WorldGuard 7 hook. Region queries run on the caller's region thread; WorldGuard's query API is a
 * read-only snapshot and is safe on the owning region thread on Folia (WorldGuard 7.0.20 reports
 * Folia support through {@code WorldGuardPlugin#isFolia}).
 */
final class WorldGuardHook implements ProtectionHook {
    private final WorldGuardPlugin plugin;
    private final RegionContainer container;

    WorldGuardHook() {
        this.plugin = WorldGuardPlugin.inst();
        if (plugin == null || !plugin.isEnabled()) {
            throw new IllegalStateException("WorldGuard is not ready");
        }
        this.container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        if (container == null) {
            throw new IllegalStateException("WorldGuard region container is not available");
        }
    }

    @Override
    public String id() {
        return "worldguard";
    }

    @Override
    public String displayName() {
        return "WorldGuard";
    }

    @Override
    public String version() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean canBuild(Player player, org.bukkit.Location location) {
        RegionQuery query = container.createQuery();
        Location at = BukkitAdapter.adapt(location);
        return query.testBuild(at, local(player), Flags.BLOCK_PLACE);
    }

    @Override
    public boolean canBreak(Player player, Block block) {
        RegionQuery query = container.createQuery();
        Location at = BukkitAdapter.adapt(block.getLocation());
        return query.testBuild(at, local(player), Flags.BLOCK_BREAK);
    }

    @Override
    public boolean canInteract(Player player, org.bukkit.Location location) {
        RegionQuery query = container.createQuery();
        Location at = BukkitAdapter.adapt(location);
        return query.testState(at, local(player), Flags.INTERACT, Flags.CHEST_ACCESS);
    }

    @Override
    public boolean canUseCommandAt(Player player, org.bukkit.Location location, String command) {
        RegionQuery query = container.createQuery();
        Location at = BukkitAdapter.adapt(location);
        LocalPlayer local = local(player);
        Set<String> blocked = query.queryValue(at, local, Flags.BLOCKED_CMDS);
        Set<String> allowed = query.queryValue(at, local, Flags.ALLOWED_CMDS);
        return CommandRules.allowed(command, blocked, allowed);
    }

    private LocalPlayer local(Player player) {
        return plugin.wrapPlayer(player);
    }
}
