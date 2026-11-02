package dev.yanianz.atrium.module.staff;

import dev.yanianz.atrium.core.menu.ItemBuilder;
import dev.yanianz.atrium.core.message.Messages;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

/** The five staff tools, tagged with a persistent data key so they are recognised everywhere. */
final class StaffTools {
    enum Tool {
        FREEZE("freeze", Material.STICK, 0),
        INSPECT("inspect", Material.SPYGLASS, 1),
        RANDOM_TELEPORT("random-teleport", Material.ENDER_PEARL, 2),
        VANISH("vanish", Material.PHANTOM_MEMBRANE, 3),
        PLAYERS("players", Material.PLAYER_HEAD, 8);

        private final String id;
        private final Material material;
        private final int slot;

        Tool(String id, Material material, int slot) {
            this.id = id;
            this.material = material;
            this.slot = slot;
        }
    }

    private final NamespacedKey key;
    private final Messages messages;

    StaffTools(org.bukkit.plugin.java.JavaPlugin plugin, Messages messages) {
        this.key = new NamespacedKey(plugin, "staff_tool");
        this.messages = messages;
    }

    void give(Player player) {
        var inventory = player.getInventory();
        for (Tool tool : Tool.values()) {
            inventory.setItem(tool.slot, create(player, tool));
        }
        player.getInventory().setHeldItemSlot(Tool.FREEZE.slot);
    }

    ItemStack create(Player viewer, Tool tool) {
        ItemStack stack = ItemBuilder.of(tool.material)
                .name(messages.render(viewer, "staff.tool." + tool.id))
                .lore(messages.render(viewer, "staff.tool." + tool.id + "-lore"))
                .hideDetails()
                .build();
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, tool.id);
        stack.setItemMeta(meta);
        return stack;
    }

    boolean isStaffItem(@Nullable ItemStack stack) {
        return toolOf(stack) != null;
    }

    @Nullable Tool toolOf(@Nullable ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return null;
        }
        PersistentDataContainer data = stack.getItemMeta().getPersistentDataContainer();
        String id = data.get(key, PersistentDataType.STRING);
        if (id == null) {
            return null;
        }
        for (Tool tool : Tool.values()) {
            if (tool.id.equals(id)) {
                return tool;
            }
        }
        return null;
    }

    /** Whether any slot (storage, armour, off hand, cursor) holds a staff tool. Player's thread. */
    boolean holdsStaffTool(Player player) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (isStaffItem(stack)) {
                return true;
            }
        }
        return isStaffItem(player.getItemOnCursor());
    }

    /** Removes every staff tool from the player's inventory, including tools hidden in bundles and shulkers. */
    void remove(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null) {
                continue;
            }
            if (scrub(stack, 0)) {
                inventory.setItem(slot, null);
            } else {
                inventory.setItem(slot, stack);
            }
        }
        ItemStack offhand = inventory.getItemInOffHand();
        if (offhand != null) {
            if (scrub(offhand, 0)) {
                inventory.setItemInOffHand(null);
            } else {
                inventory.setItemInOffHand(offhand);
            }
        }
    }

    /**
     * Removes staff tools nested in a kept item (bundle or shulker box) and reports whether the item
     * itself is a staff tool, in which case the caller must drop it. Bounded recursion.
     */
    boolean scrub(@Nullable ItemStack stack, int depth) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        if (isStaffItem(stack)) {
            return true;
        }
        if (depth >= 4 || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof BundleMeta bundle && bundle.hasItems()) {
            List<ItemStack> kept = new ArrayList<>();
            boolean changed = false;
            for (ItemStack child : bundle.getItems()) {
                if (scrub(child, depth + 1)) {
                    changed = true;
                } else {
                    kept.add(child);
                }
            }
            if (changed) {
                bundle.setItems(kept);
                stack.setItemMeta(bundle);
            }
        }
        if (meta instanceof BlockStateMeta blockState && blockState.hasBlockState()
                && blockState.getBlockState() instanceof ShulkerBox box) {
            Inventory contents = box.getInventory();
            ItemStack[] items = contents.getContents();
            boolean changed = false;
            for (int slot = 0; slot < items.length; slot++) {
                ItemStack child = items[slot];
                if (child == null) {
                    continue;
                }
                if (scrub(child, depth + 1)) {
                    contents.setItem(slot, null);
                    changed = true;
                } else {
                    contents.setItem(slot, child);
                }
            }
            if (changed) {
                blockState.setBlockState(box);
                stack.setItemMeta(blockState);
            }
        }
        return false;
    }
}
