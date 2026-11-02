package dev.yanianz.atrium.module.worth;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Server-independent projection of the identifying data of one item stack. Pricing works only on this,
 * so it is fully unit-testable. {@link ItemFactsReader} builds it from a live {@code ItemStack}.
 *
 * @param material         lower-case material name, e.g. {@code diamond_sword}
 * @param block            whether the material is a placeable block
 * @param itemModel        overridden item model key, {@code null} when the default model is used
 * @param customModelData  canonical custom model data tokens (see {@link #cmdToken}); empty = none
 * @param name             plain-text custom or overridden item name, {@code null} when none
 * @param lore             plain-text lore lines; empty = none
 * @param enchantments     enchantment key (without {@code minecraft:}) to level, incl. stored book enchantments
 * @param damage           current damage
 * @param maxDamage        maximum damage, 0 when not damageable
 * @param otherComponents  keys of other overridden data components (custom_data, potion_contents, ...)
 * @param contents         container contents (shulker boxes); empty = no contents
 */
public record ItemFacts(
        String material,
        boolean block,
        @Nullable String itemModel,
        List<String> customModelData,
        @Nullable String name,
        List<String> lore,
        Map<String, Integer> enchantments,
        int damage,
        int maxDamage,
        Set<String> otherComponents,
        List<Content> contents) {

    public ItemFacts {
        Objects.requireNonNull(material, "material");
        customModelData = List.copyOf(customModelData);
        lore = List.copyOf(lore);
        enchantments = Map.copyOf(enchantments);
        otherComponents = Set.copyOf(otherComponents);
        contents = List.copyOf(contents);
    }

    /** One stack inside a container item. */
    public record Content(ItemFacts facts, int amount) {}

    public static ItemFacts plain(String material) {
        return new ItemFacts(material, false, null, List.of(), null, List.of(), Map.of(), 0, 0, Set.of(), List.of());
    }

    public ItemFacts withBlock(boolean value) {
        return new ItemFacts(material, value, itemModel, customModelData, name, lore, enchantments, damage, maxDamage, otherComponents, contents);
    }

    public ItemFacts withItemModel(@Nullable String value) {
        return new ItemFacts(material, block, value, customModelData, name, lore, enchantments, damage, maxDamage, otherComponents, contents);
    }

    public ItemFacts withCustomModelData(List<String> value) {
        return new ItemFacts(material, block, itemModel, value, name, lore, enchantments, damage, maxDamage, otherComponents, contents);
    }

    public ItemFacts withName(@Nullable String value) {
        return new ItemFacts(material, block, itemModel, customModelData, value, lore, enchantments, damage, maxDamage, otherComponents, contents);
    }

    public ItemFacts withLore(List<String> value) {
        return new ItemFacts(material, block, itemModel, customModelData, name, value, enchantments, damage, maxDamage, otherComponents, contents);
    }

    public ItemFacts withEnchantments(Map<String, Integer> value) {
        return new ItemFacts(material, block, itemModel, customModelData, name, lore, value, damage, maxDamage, otherComponents, contents);
    }

    public ItemFacts withDamage(int value, int max) {
        return new ItemFacts(material, block, itemModel, customModelData, name, lore, enchantments, value, max, otherComponents, contents);
    }

    public ItemFacts withOtherComponents(Set<String> value) {
        return new ItemFacts(material, block, itemModel, customModelData, name, lore, enchantments, damage, maxDamage, value, contents);
    }

    public ItemFacts withContents(List<Content> value) {
        return new ItemFacts(material, block, itemModel, customModelData, name, lore, enchantments, damage, maxDamage, otherComponents, value);
    }

    /** Canonical token of a custom-model-data float: {@code 1001} for 1001.0, {@code 1.5} otherwise. */
    public static String cmdToken(float value) {
        if (value == Math.rint(value) && !Float.isInfinite(value) && Math.abs(value) < 1e9) {
            return Long.toString((long) value);
        }
        return Float.toString(value);
    }

    /** Normalises a token written by an admin ({@code 1001}, {@code 1001.0}, {@code s:ruby}, {@code b:true}). */
    public static String normaliseToken(String raw) {
        String token = raw.trim();
        if (token.startsWith("s:") || token.startsWith("b:") || token.startsWith("c:")) {
            return token;
        }
        try {
            return cmdToken(Float.parseFloat(token));
        } catch (NumberFormatException e) {
            return "s:" + token;
        }
    }
}
