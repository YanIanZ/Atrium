package dev.yanianz.atrium.module.worldtools;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Selection wand: left-click sets corner 1, right-click sets corner 2. Only the block the player clicked is
 * read, on the player's own thread, and only while the wand is held; nothing is ever changed in the world.
 */
final class SelectionListener implements Listener {
    private final ModuleContext ctx;
    private final ConfigHandle<WorldToolsConfig> config;
    private final Selections selections;

    SelectionListener(ModuleContext ctx, ConfigHandle<WorldToolsConfig> config, Selections selections) {
        this.ctx = ctx;
        this.config = config;
        this.selections = selections;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        WorldToolsConfig cfg = config.get();
        if (!cfg.selectionWandEnabled() || !isWand(event.getItem(), cfg)) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission(WorldToolsModule.PERM_SELECT)) {
            return;
        }
        int corner;
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            corner = 1;
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            corner = 2;
        } else {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        event.setCancelled(true);
        Location location = block.getLocation();
        Cuboid.Point point = new Cuboid.Point(location.getWorld() == null ? "" : location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
        selections.setCorner(player.getUniqueId(), corner, point);
        String key = selections.hasBoth(player.getUniqueId()) ? "worldtools.select-both" : "worldtools.select-corner";
        ctx.messages().send(player, key, Msg.number("corner", corner),
                Msg.number("x", point.x()), Msg.number("y", point.y()), Msg.number("z", point.z()));
        if (selections.hasBoth(player.getUniqueId())) {
            selections.selection(player.getUniqueId()).ifPresent(cuboid -> {
                if (!cuboid.fits(cfg.maxSelectionVolume())) {
                    ctx.messages().send(player, "worldtools.select-too-big", Msg.number("volume", cuboid.volume()),
                            Msg.number("max", cfg.maxSelectionVolume()));
                } else {
                    ctx.messages().send(player, "worldtools.select-size", Msg.number("volume", cuboid.volume()));
                }
            });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        selections.forget(event.getPlayer().getUniqueId());
    }

    /** Whether an item is the configured wand; comparing the material keeps the check free of NBT. */
    static boolean isWand(ItemStack item, WorldToolsConfig cfg) {
        if (item == null || item.isEmpty()) {
            return false;
        }
        return item.getType() == cfg.selectionWandMaterial();
    }

    static ItemStack wand(WorldToolsConfig cfg) {
        return new ItemStack(cfg.selectionWandMaterial());
    }

    static Material resolveWand(String name) {
        Material material = name == null ? null : Material.matchMaterial(name.strip());
        return material == null || !material.isItem() ? Material.GOLDEN_HOE : material;
    }
}
