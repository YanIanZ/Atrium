package dev.yanianz.atrium.module.worth;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One price line of worth.yml.
 *
 * <p>Matching is exact per attribute: when the entry specifies an attribute, the item must have exactly that
 * value; when it does not, the item must <em>not</em> carry that attribute at all, unless the attribute is
 * listed in {@code accept}. A vanilla price therefore never pays for a custom-model-data, renamed, lored,
 * enchanted or plugin-tagged item.
 */
public record PriceEntry(
        String id,
        String material,
        BigDecimal price,
        @Nullable String itemModel,
        @Nullable List<String> customModelData,
        @Nullable String name,
        @Nullable Map<String, Integer> enchantments,
        Set<Attribute> accept) {

    public PriceEntry {
        material = material.toLowerCase(Locale.ROOT);
        customModelData = customModelData == null ? null : List.copyOf(customModelData);
        enchantments = enchantments == null ? null : Map.copyOf(enchantments);
        accept = accept.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(accept));
    }

    /** Attributes that make an item differ from its vanilla form. */
    public enum Attribute {
        ITEM_MODEL("item-model"),
        CUSTOM_MODEL_DATA("custom-model-data"),
        NAME("name"),
        LORE("lore"),
        ENCHANTMENTS("enchantments"),
        COMPONENTS("components");

        private final String key;

        Attribute(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public static Optional<Attribute> byKey(String key) {
            for (Attribute a : values()) {
                if (a.key.equalsIgnoreCase(key.trim())) {
                    return Optional.of(a);
                }
            }
            return Optional.empty();
        }
    }

    public static PriceEntry plain(String material, BigDecimal price) {
        return new PriceEntry(material.toLowerCase(Locale.ROOT), material, price, null, null, null, null, Set.of());
    }

    /** True when no identifying attribute is specified or accepted. */
    public boolean isPlain() {
        return itemModel == null && customModelData == null && name == null && enchantments == null && accept.isEmpty();
    }

    /** Number of exactly specified attributes; the most specific matching entry wins. */
    public int specificity() {
        int n = 0;
        if (itemModel != null) n++;
        if (customModelData != null) n++;
        if (name != null) n++;
        if (enchantments != null) n++;
        return n;
    }

    public boolean matches(ItemFacts item) {
        if (!material.equals(item.material())) {
            return false;
        }
        if (!attribute(Attribute.ITEM_MODEL, itemModel, item.itemModel(), item.itemModel() != null)) {
            return false;
        }
        if (!attribute(Attribute.CUSTOM_MODEL_DATA, customModelData, item.customModelData(),
                !item.customModelData().isEmpty())) {
            return false;
        }
        if (!attribute(Attribute.NAME, name, item.name(), item.name() != null)) {
            return false;
        }
        if (!attribute(Attribute.ENCHANTMENTS, enchantments, item.enchantments(), !item.enchantments().isEmpty())) {
            return false;
        }
        if (!item.lore().isEmpty() && !accept.contains(Attribute.LORE)) {
            return false;
        }
        return item.otherComponents().isEmpty() || accept.contains(Attribute.COMPONENTS);
    }

    private boolean attribute(Attribute attribute, @Nullable Object wanted, @Nullable Object actual, boolean present) {
        if (wanted != null) {
            return wanted.equals(actual);
        }
        return !present || accept.contains(attribute);
    }
}
