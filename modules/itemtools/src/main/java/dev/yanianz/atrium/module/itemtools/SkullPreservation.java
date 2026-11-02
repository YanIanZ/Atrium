package dev.yanianz.atrium.module.itemtools;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.ModuleContext;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Keeps the extra data of a skull (custom name, lore, enchantments, components) through place and break.
 *
 * <p>The placed item is serialized into the skull block's own persistent data. Vanilla drops a plain skull;
 * on break the stored item is dropped instead, after checking that the block is still the same skull type
 * and skin. The data lives in the block entity, so it disappears with the block: a skull placed later at
 * the same coordinates can never inherit an old name. Skulls without extra data keep the vanilla path.
 */
final class SkullPreservation implements Listener {
    private final ModuleContext ctx;
    private final ConfigHandle<ItemToolsConfig> config;
    private final NamespacedKey key;

    SkullPreservation(ModuleContext ctx, ConfigHandle<ItemToolsConfig> config) {
        this.ctx = ctx;
        this.config = config;
        this.key = new NamespacedKey(ctx.plugin(), "skull_item");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!config.get().preserveSkullData) {
            return;
        }
        Block block = event.getBlockPlaced();
        if (!isSkull(block.getType()) || !(block.getState() instanceof Skull skull)) {
            return;
        }
        ItemStack placed = event.getItemInHand();
        if (placed == null || placed.isEmpty() || !hasExtraData(placed)) {
            return;
        }
        byte[] bytes;
        try {
            bytes = placed.asOne().serializeAsBytes();
        } catch (RuntimeException e) {
            ctx.logger().warn("Could not store the data of a placed skull at {}", block.getLocation(), e);
            return;
        }
        skull.getPersistentDataContainer().set(key, PersistentDataType.BYTE_ARRAY, bytes);
        skull.update(true, false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!config.get().preserveSkullData) {
            return;
        }
        Block block = event.getBlock();
        if (!isSkull(block.getType()) || !(block.getState() instanceof Skull skull)) {
            return;
        }
        byte[] bytes = skull.getPersistentDataContainer().get(key, PersistentDataType.BYTE_ARRAY);
        if (bytes == null || bytes.length == 0) {
            return;
        }
        ItemStack stored;
        try {
            stored = ItemStack.deserializeBytes(bytes);
        } catch (RuntimeException e) {
            ctx.logger().warn("Could not restore the data of a broken skull at {}", block.getLocation(), e);
            return;
        }
        if (stored == null || stored.isEmpty() || !matches(stored, block.getType(), skull)) {
            return;
        }
        // One block was broken and exactly one item is dropped: no dupe is possible.
        ItemStack drop = stored.clone();
        drop.setAmount(1);
        event.setDropItems(false);
        block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), drop);
        ctx.logger().debug("Restored the data of a skull broken at {}", block.getLocation());
    }

    /** The block and the stored item must describe the same skull, including the skin when both have one. */
    static boolean matches(ItemStack stored, Material blockType, Skull skull) {
        if (standing(stored.getType()) != standing(blockType)) {
            return false;
        }
        String expected = texture(stored);
        String actual = texture(skull.getProfile().properties());
        return expected == null || actual == null || expected.equals(actual);
    }

    /** The standing variant of a wall skull (and every other skull type unchanged). */
    static Material standing(Material type) {
        String name = type.name();
        if (!name.contains("_WALL_")) {
            return type;
        }
        Material standing = Material.matchMaterial(name.replace("_WALL_", "_"));
        return standing == null ? type : standing;
    }

    static boolean isSkull(Material type) {
        String name = type.name();
        return name.endsWith("_SKULL") || name.endsWith("_HEAD");
    }

    /** True when breaking the block would lose something vanilla does not keep. */
    static boolean hasExtraData(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        if (meta.hasCustomName() || meta.hasLore() || meta.hasEnchants() || meta.hasCustomModelDataComponent()
                || meta.isUnbreakable() || meta.hasEnchantmentGlintOverride()
                || !meta.getPersistentDataContainer().isEmpty() || !meta.getItemFlags().isEmpty()) {
            return true;
        }
        if (meta instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta storage && storage.hasStoredEnchants()) {
            return true;
        }
        return meta instanceof org.bukkit.inventory.meta.ArmorMeta armor && armor.hasTrim();
    }

    private static String texture(ItemStack item) {
        return item.getItemMeta() instanceof SkullMeta meta ? texture(meta.getPlayerProfile()) : null;
    }

    private static String texture(java.util.Collection<ProfileProperty> properties) {
        for (ProfileProperty property : properties) {
            if ("textures".equals(property.getName())) {
                return property.getValue();
            }
        }
        return null;
    }

    private static String texture(PlayerProfile profile) {
        return profile == null ? null : texture(profile.getProperties());
    }
}
