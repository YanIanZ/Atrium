package dev.yanianz.atrium.module.inspect;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import io.papermc.paper.datacomponent.item.ItemEnchantments;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.persistence.PersistentDataContainerView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Tameable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Read-only snapshots taken on the owning region/entity thread and formatted for chat. */
final class Snapshots {
    private Snapshots() {}

    record BlockSnapshot(String world, int x, int y, int z, String material, String data, int blockLight,
            int skyLight, String biome, String tileEntity, List<String> tileData, String thread) {}

    record EntitySnapshot(String type, UUID uuid, String customName, boolean hasCustomName, boolean living,
            double health, double maxHealth, int ageTicks, boolean persistent, List<String> passengers,
            String vehicle, String owner, String snbt, String thread) {}

    record ItemComponent(String key, String value) {}

    record ItemSnapshot(String id, int amount, String displayName, boolean customName,
            List<ItemComponent> components, String snbt) {}

    record LastSeenRow(UUID uuid, String name, long lastSeen, boolean online) {}

    record LastOnlinePage(int total, int page, int pages, List<LastSeenRow> rows) {}

    /** Must run on the block's region thread. */
    static BlockSnapshot block(Block block) {
        BlockState state = block.getState();
        String tile = state instanceof TileState ? shortType(state.getType().name()) : "";
        return new BlockSnapshot(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                shortType(block.getType().name()), InspectFormat.blockData(block.getBlockData().getAsString()),
                block.getLightFromBlocks(), block.getLightFromSky(), block.getBiome().getKey().toString(), tile,
                tileData(state), Thread.currentThread().getName());
    }

    private static List<String> tileData(BlockState state) {
        if (!(state instanceof TileState tile)) {
            return List.of();
        }
        PersistentDataContainerView container = tile.getPersistentDataContainer();
        List<String> lines = new ArrayList<>();
        for (NamespacedKey key : container.getKeys()) {
            try {
                lines.add(key + " = " + pdcValue(container, key));
            } catch (RuntimeException e) {
                // A stored value type this server cannot read: report it instead of hiding the key.
                lines.add(key + " = <unreadable " + e.getClass().getSimpleName() + ">");
            }
        }
        lines.sort(Comparator.naturalOrder());
        return List.copyOf(lines);
    }

    private static String pdcValue(PersistentDataContainerView container, NamespacedKey key) {
        if (container.has(key, PersistentDataType.STRING)) {
            return container.get(key, PersistentDataType.STRING);
        }
        if (container.has(key, PersistentDataType.BYTE)) {
            return String.valueOf(container.get(key, PersistentDataType.BYTE));
        }
        if (container.has(key, PersistentDataType.SHORT)) {
            return String.valueOf(container.get(key, PersistentDataType.SHORT));
        }
        if (container.has(key, PersistentDataType.INTEGER)) {
            return String.valueOf(container.get(key, PersistentDataType.INTEGER));
        }
        if (container.has(key, PersistentDataType.LONG)) {
            return String.valueOf(container.get(key, PersistentDataType.LONG));
        }
        if (container.has(key, PersistentDataType.FLOAT)) {
            return String.valueOf(container.get(key, PersistentDataType.FLOAT));
        }
        if (container.has(key, PersistentDataType.DOUBLE)) {
            return String.valueOf(container.get(key, PersistentDataType.DOUBLE));
        }
        if (container.has(key, PersistentDataType.BOOLEAN)) {
            return String.valueOf(container.get(key, PersistentDataType.BOOLEAN));
        }
        if (container.has(key, PersistentDataType.BYTE_ARRAY)) {
            return Arrays.toString(container.get(key, PersistentDataType.BYTE_ARRAY));
        }
        if (container.has(key, PersistentDataType.INTEGER_ARRAY)) {
            return Arrays.toString(container.get(key, PersistentDataType.INTEGER_ARRAY));
        }
        if (container.has(key, PersistentDataType.LONG_ARRAY)) {
            return Arrays.toString(container.get(key, PersistentDataType.LONG_ARRAY));
        }
        if (container.has(key, PersistentDataType.TAG_CONTAINER)) {
            return "<nested container>";
        }
        return "<unknown type>";
    }

    /** Must run on the entity's own thread. */
    static EntitySnapshot entity(Entity entity) {
        Component custom = entity.customName();
        String customName = custom == null ? "" : InspectFormat.plain(custom);
        boolean living = entity instanceof LivingEntity;
        double health = -1;
        double maxHealth = -1;
        if (entity instanceof LivingEntity livingEntity) {
            health = livingEntity.getHealth();
            var attribute = livingEntity.getAttribute(Attribute.MAX_HEALTH);
            maxHealth = attribute == null ? -1 : attribute.getValue();
        }
        List<String> passengers = entity.getPassengers().stream()
                .map(passenger -> shortType(passenger.getType().name()) + " " + passenger.getUniqueId())
                .toList();
        Entity vehicle = entity.getVehicle();
        String owner = "";
        if (entity instanceof Tameable tameable && tameable.getOwner() != null) {
            var tamer = tameable.getOwner();
            UUID ownerId = tamer.getUniqueId();
            owner = ownerId == null ? tamer.getName() : tamer.getName() + " (" + ownerId + ")";
        }
        String snbt;
        try {
            snbt = entity.getAsString();
        } catch (RuntimeException e) {
            snbt = "<unavailable " + e.getClass().getSimpleName() + ": " + e.getMessage() + ">";
        }
        return new EntitySnapshot(entity.getType().getKey().toString(), entity.getUniqueId(), customName,
                custom != null, living, health, maxHealth, entity.getTicksLived(), entity.isPersistent(), passengers,
                vehicle == null ? "" : shortType(vehicle.getType().name()) + " " + vehicle.getUniqueId(), owner, snbt,
                Thread.currentThread().getName());
    }

    /** Must run on the holder's thread. */
    static ItemSnapshot item(ItemStack stack) {
        String id = stack.getType().getKey().toString();
        List<ItemComponent> components = new ArrayList<>();
        Map<String, String> snbt = new LinkedHashMap<>();
        for (DataComponentType type : stack.getDataTypes()) {
            String key = type.key().toString();
            String value;
            try {
                value = componentValue(stack, type);
            } catch (RuntimeException e) {
                value = "<unreadable " + e.getClass().getSimpleName() + ">";
            }
            components.add(new ItemComponent(key, value));
            snbt.put(key, value);
        }
        components.sort(Comparator.comparing(ItemComponent::key));
        return new ItemSnapshot(id, stack.getAmount(), InspectFormat.plain(stack.displayName()),
                stack.hasData(DataComponentTypes.CUSTOM_NAME), List.copyOf(components),
                InspectFormat.snbtItem(id, stack.getAmount(), snbt));
    }

    private static String componentValue(ItemStack stack, DataComponentType type) {
        if (type == DataComponentTypes.UNBREAKABLE) {
            return "";
        }
        if (!(type instanceof DataComponentType.Valued<?> valued)) {
            return "";
        }
        Object value = read(stack, valued);
        if (value == null) {
            return "null";
        }
        if (value instanceof Component component) {
            return InspectFormat.plain(component);
        }
        if (value instanceof ItemLore lore) {
            return String.join(" | ", lore.lines().stream().map(InspectFormat::plain).toList());
        }
        if (value instanceof ItemEnchantments enchantments) {
            return enchantments.enchantments().entrySet().stream()
                    .map(entry -> enchantmentName(entry.getKey()) + "=" + entry.getValue())
                    .sorted()
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
        }
        if (value instanceof CustomModelData modelData) {
            return "floats=" + modelData.floats() + ", flags=" + modelData.flags() + ", strings=" + modelData.strings()
                    + ", colors=" + modelData.colors().size() + " color(s)";
        }
        if (value instanceof ItemContainerContents contents) {
            return contents.contents().stream()
                    .map(item -> item.getType().getKey() + " x" + item.getAmount())
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
        }
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T read(ItemStack stack, DataComponentType.Valued<T> type) {
        return stack.getData(type);
    }

    private static String enchantmentName(Enchantment enchantment) {
        return enchantment.getKey().toString();
    }

    private static String shortType(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
