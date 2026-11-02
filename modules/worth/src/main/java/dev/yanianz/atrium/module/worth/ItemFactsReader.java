package dev.yanianz.atrium.module.worth;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import io.papermc.paper.datacomponent.item.ItemEnchantments;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Color;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

/** Builds {@link ItemFacts} from a live item. Call on the thread that owns the item's holder. */
final class ItemFactsReader {
    /** Components that are compared explicitly or never identify a different item. */
    private static final Set<String> HANDLED = Set.of(
            "minecraft:custom_name", "minecraft:item_name", "minecraft:lore", "minecraft:custom_model_data",
            "minecraft:item_model", "minecraft:enchantments", "minecraft:stored_enchantments",
            "minecraft:damage", "minecraft:container");

    private final Set<String> ignoredComponents;

    ItemFactsReader(List<String> ignoredComponents) {
        Set<String> set = new HashSet<>();
        for (String key : ignoredComponents) {
            String k = key.trim().toLowerCase(Locale.ROOT);
            set.add(k.contains(":") ? k : "minecraft:" + k);
        }
        this.ignoredComponents = Set.copyOf(set);
    }

    ItemFacts read(ItemStack stack) {
        return read(stack, 0);
    }

    private ItemFacts read(ItemStack stack, int depth) {
        String material = stack.getType().name().toLowerCase(Locale.ROOT);
        PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();

        String itemModel = null;
        if (stack.isDataOverridden(DataComponentTypes.ITEM_MODEL)) {
            Key key = stack.getData(DataComponentTypes.ITEM_MODEL);
            itemModel = key == null ? "none" : key.asString();
        }

        List<String> cmd = new ArrayList<>();
        CustomModelData data = stack.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
        if (data != null) {
            for (Float f : data.floats()) cmd.add(ItemFacts.cmdToken(f));
            for (String s : data.strings()) cmd.add("s:" + s);
            for (Boolean b : data.flags()) cmd.add("b:" + b);
            for (Color c : data.colors()) cmd.add(String.format(Locale.ROOT, "c:#%06x", c.asRGB()));
        }

        String name = null;
        Component custom = stack.getData(DataComponentTypes.CUSTOM_NAME);
        if (custom != null) {
            name = plain.serialize(custom);
        } else if (stack.isDataOverridden(DataComponentTypes.ITEM_NAME)) {
            Component itemName = stack.getData(DataComponentTypes.ITEM_NAME);
            name = itemName == null ? "" : plain.serialize(itemName);
        }

        List<String> lore = new ArrayList<>();
        ItemLore itemLore = stack.getData(DataComponentTypes.LORE);
        if (itemLore != null) {
            for (Component line : itemLore.lines()) lore.add(plain.serialize(line));
        }

        Map<String, Integer> enchantments = new LinkedHashMap<>();
        addEnchantments(enchantments, stack.getData(DataComponentTypes.ENCHANTMENTS));
        addEnchantments(enchantments, stack.getData(DataComponentTypes.STORED_ENCHANTMENTS));

        Integer damage = stack.getData(DataComponentTypes.DAMAGE);
        Integer maxDamage = stack.getData(DataComponentTypes.MAX_DAMAGE);

        Set<String> other = new HashSet<>();
        for (DataComponentType type : stack.getDataTypes()) {
            String key = type.getKey().asString();
            if (!HANDLED.contains(key) && !ignoredComponents.contains(key) && stack.isDataOverridden(type)) {
                other.add(key);
            }
        }

        List<ItemFacts.Content> contents = new ArrayList<>();
        ItemContainerContents container = stack.getData(DataComponentTypes.CONTAINER);
        if (container != null) {
            for (ItemStack inner : container.contents()) {
                if (inner == null || inner.isEmpty()) continue;
                if (depth >= 2) {
                    // Absurdly nested: mark as unknown data so it never sells.
                    other.add("atrium:nested_container");
                    break;
                }
                contents.add(new ItemFacts.Content(read(inner, depth + 1), inner.getAmount()));
            }
        }

        return new ItemFacts(material, stack.getType().isBlock(), itemModel, cmd, name, lore, enchantments,
                damage == null ? 0 : damage, maxDamage == null ? 0 : maxDamage, other, contents);
    }

    private static void addEnchantments(Map<String, Integer> out, ItemEnchantments enchantments) {
        if (enchantments == null) return;
        for (Map.Entry<Enchantment, Integer> e : enchantments.enchantments().entrySet()) {
            out.merge(PriceTable.normaliseEnchantment(e.getKey().getKey().asString()), e.getValue(), Math::max);
        }
    }
}
