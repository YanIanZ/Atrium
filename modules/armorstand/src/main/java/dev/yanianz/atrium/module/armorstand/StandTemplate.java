package dev.yanianz.atrium.module.armorstand;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * A complete armor stand snapshot: pose, flags, custom name and equipment. Used as a placement template:
 * an armor stand item tagged with a template name creates a stand exactly like the saved one.
 *
 * <p>Equipment is copied on apply, so two stands never share an {@link ItemStack} instance. The name is
 * stored as MiniMessage text with click/hover data removed; a template is written by an admin, but a saved
 * stand may carry a name a player typed, so it is never trusted as interactive text.
 */
record StandTemplate(String name, Pose pose, Set<Toggle> toggles, @Nullable String customName,
        Map<EquipmentSlot, ItemStack> equipment) {

    /** Slots a template stores. */
    static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND};

    StandTemplate {
        toggles = toggles.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(toggles));
        Map<EquipmentSlot, ItemStack> copy = new EnumMap<>(EquipmentSlot.class);
        equipment.forEach((slot, item) -> {
            if (item != null && !item.isEmpty() && item.getType() != Material.AIR) {
                copy.put(slot, item.clone());
            }
        });
        equipment = Map.copyOf(copy);
    }

    static StandTemplate capture(String name, ArmorStand stand) {
        Map<EquipmentSlot, ItemStack> items = new EnumMap<>(EquipmentSlot.class);
        var holder = stand.getEquipment();
        if (holder != null) {
            for (EquipmentSlot slot : SLOTS) {
                ItemStack item = holder.getItem(slot);
                if (item != null && !item.isEmpty()) {
                    items.put(slot, item.clone());
                }
            }
        }
        Component customName = stand.customName();
        return new StandTemplate(name, Pose.of(stand), Toggle.enabled(stand),
                customName == null ? null : MiniMessage.miniMessage().serialize(strip(customName)), items);
    }

    void apply(ArmorStand stand) {
        pose.apply(stand);
        Toggle.applyAll(stand, toggles);
        if (customName != null && !customName.isBlank()) {
            try {
                stand.customName(MiniMessage.miniMessage().deserialize(customName));
            } catch (RuntimeException e) {
                // A malformed template must not stop the rest of the template from being applied.
                stand.customName(Component.text(customName));
            }
        }
        var holder = stand.getEquipment();
        if (holder == null) {
            return;
        }
        for (EquipmentSlot slot : SLOTS) {
            ItemStack item = equipment.get(slot);
            holder.setItem(slot, item == null ? null : item.clone());
        }
    }

    private static Component strip(Component component) {
        Component plain = component.clickEvent(null).hoverEvent(null).insertion(null);
        if (plain.children().isEmpty()) {
            return plain;
        }
        return plain.children(plain.children().stream().map(StandTemplate::strip).toList());
    }

    /** Toggle keys for storage, sorted for a stable file. */
    Set<String> toggleKeys() {
        return toggles.stream().map(Toggle::key).collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
    }
}
