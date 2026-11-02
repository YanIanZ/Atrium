package dev.yanianz.atrium.module.search;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.slf4j.Logger;

/**
 * Reads one player's inventory and ender chest. Always called on that player's own thread via
 * {@code supplyOnEntity}: Folia entities are region-owned. Shulker box contents are read from the
 * {@link BlockStateMeta} snapshot only; no block is placed or modified.
 */
final class ItemScan {
    private static final int PLACE_INVENTORY = 0;
    private static final int PLACE_ENDER = 1;
    private static final int PLACE_SHULKER = 2;

    record PlayerRow(String name, long total, int stacks, long inventory, long ender, long shulker) {}

    private ItemScan() {}

    static PlayerRow scan(Player player, ItemFilter filter, Logger logger) {
        Counts counts = new Counts();
        collect(player.getInventory().getContents(), filter, counts, PLACE_INVENTORY, logger);
        collect(player.getEnderChest().getContents(), filter, counts, PLACE_ENDER, logger);
        return new PlayerRow(player.getName(), counts.total, counts.stacks,
                counts.inventory, counts.ender, counts.shulker);
    }

    private static void collect(ItemStack[] contents, ItemFilter filter, Counts counts, int place, Logger logger) {
        if (contents == null) {
            return;
        }
        for (ItemStack stack : contents) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ItemMeta meta = stack.getItemMeta();
            ItemFilter.ItemKey key = new ItemFilter.ItemKey(stack.getType(), plainName(meta), customModelData(meta));
            if (filter.matches(key)) {
                counts.add(place, stack.getAmount());
            }
            if (meta instanceof BlockStateMeta blockStateMeta) {
                collectShulker(blockStateMeta, filter, counts, logger);
            }
        }
    }

    private static void collectShulker(BlockStateMeta meta, ItemFilter filter, Counts counts, Logger logger) {
        if (!meta.hasBlockState()) {
            return;
        }
        BlockState state;
        try {
            state = meta.getBlockState();
        } catch (RuntimeException e) {
            logger.warn("Cannot read a shulker box item's block state; its contents are skipped", e);
            return;
        }
        if (!(state instanceof ShulkerBox shulker)) {
            return;
        }
        ItemStack[] contents;
        try {
            contents = shulker.getSnapshotInventory().getContents();
        } catch (RuntimeException e) {
            logger.warn("Cannot read the contents of a shulker box item; it is skipped", e);
            return;
        }
        collectShulkerContents(contents, filter, counts);
    }

    private static void collectShulkerContents(ItemStack[] contents, ItemFilter filter, Counts counts) {
        for (ItemStack stack : contents) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ItemMeta meta = stack.getItemMeta();
            ItemFilter.ItemKey key = new ItemFilter.ItemKey(stack.getType(), plainName(meta), customModelData(meta));
            if (filter.matches(key)) {
                counts.add(PLACE_SHULKER, stack.getAmount());
            }
        }
    }

    private static String plainName(ItemMeta meta) {
        if (meta == null) {
            return null;
        }
        Component component = null;
        if (meta.hasDisplayName()) {
            component = meta.displayName();
        } else if (meta.hasItemName()) {
            component = meta.itemName();
        }
        return component == null ? null : PlainTextComponentSerializer.plainText().serialize(component);
    }

    /**
     * CustomModelData as read from the component (the modern form), with the legacy int as fallback.
     * Returns {@code null} when the item has no custom model data at all.
     */
    static Integer customModelData(ItemMeta meta) {
        if (meta == null) {
            return null;
        }
        if (meta.hasCustomModelDataComponent()) {
            List<Float> floats = meta.getCustomModelDataComponent().getFloats();
            if (!floats.isEmpty() && floats.getFirst() != null) {
                return floats.getFirst().intValue();
            }
        }
        return legacyCustomModelData(meta);
    }

    /** Older items only fill the deprecated int field, not the component. */
    @SuppressWarnings("deprecation")
    private static Integer legacyCustomModelData(ItemMeta meta) {
        return meta.hasCustomModelData() ? meta.getCustomModelData() : null;
    }

    private static final class Counts {
        long total;
        int stacks;
        long inventory;
        long ender;
        long shulker;

        void add(int place, int amount) {
            total += amount;
            stacks++;
            switch (place) {
                case PLACE_INVENTORY -> inventory += amount;
                case PLACE_ENDER -> ender += amount;
                case PLACE_SHULKER -> shulker += amount;
                default -> throw new IllegalStateException("unknown place " + place);
            }
        }
    }

}
