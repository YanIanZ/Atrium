package dev.yanianz.atrium.module.worth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Immutable price list plus the valuation rules. Thread-safe; swapped atomically on reload. */
public final class PriceTable {
    /** How items carrying container contents (filled shulker boxes) are valued. */
    public enum ContainerMode { EMPTY_ONLY, SUM_CONTENTS }

    public record Options(
            boolean devalueByDurability,
            boolean requireFullDurability,
            boolean blockCustomNames,
            List<String> unsellableLore,
            ContainerMode containerMode,
            int scale) {
        public Options {
            unsellableLore = unsellableLore.stream().map(s -> s.toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList();
            if (scale < 0 || scale > 10) {
                throw new IllegalArgumentException("scale must be 0-10");
            }
        }

        public static Options defaults() {
            return new Options(true, false, false, List.of(), ContainerMode.EMPTY_ONLY, 2);
        }
    }

    private static final int MAX_DEPTH = 2;

    private final Map<String, List<PriceEntry>> byMaterial;
    private final List<PriceEntry> entries;
    private final Map<String, BigDecimal> enchantmentValues;
    private final Options options;

    public PriceTable(List<PriceEntry> entries, Map<String, BigDecimal> enchantmentValues, Options options) {
        Map<String, List<PriceEntry>> map = new HashMap<>();
        for (PriceEntry entry : entries) {
            map.computeIfAbsent(entry.material(), k -> new ArrayList<>()).add(entry);
        }
        map.replaceAll((k, v) -> List.copyOf(v));
        this.byMaterial = Map.copyOf(map);
        this.entries = List.copyOf(entries);
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        enchantmentValues.forEach((k, v) -> values.put(normaliseEnchantment(k), v));
        this.enchantmentValues = Collections.unmodifiableMap(values);
        this.options = options;
    }

    public static PriceTable empty() {
        return new PriceTable(List.of(), Map.of(), Options.defaults());
    }

    public static String normaliseEnchantment(String key) {
        String k = key.trim().toLowerCase(Locale.ROOT);
        return k.startsWith("minecraft:") ? k.substring("minecraft:".length()) : k;
    }

    public List<PriceEntry> entries() {
        return entries;
    }

    public Map<String, BigDecimal> enchantmentValues() {
        return enchantmentValues;
    }

    public Options options() {
        return options;
    }

    /** Entries priced for a material (any attributes). */
    public List<PriceEntry> forMaterial(String material) {
        return byMaterial.getOrDefault(material.toLowerCase(Locale.ROOT), List.of());
    }

    /** The plain (vanilla) entry of a material, if any. */
    public Optional<PriceEntry> plain(String material) {
        return forMaterial(material).stream().filter(PriceEntry::isPlain).findFirst();
    }

    /** The most specific matching entry; ties go to the entry defined first. */
    public Optional<PriceEntry> match(ItemFacts item) {
        PriceEntry best = null;
        for (PriceEntry entry : forMaterial(item.material())) {
            if (entry.matches(item) && (best == null || entry.specificity() > best.specificity())) {
                best = entry;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Sell value of one item (amount 1), or empty when the item cannot be sold. Never returns zero or a
     * negative value.
     */
    public Optional<BigDecimal> unitPrice(ItemFacts item) {
        return unitPrice(item, 0);
    }

    private Optional<BigDecimal> unitPrice(ItemFacts item, int depth) {
        if (depth > MAX_DEPTH) {
            return Optional.empty();
        }
        for (String line : item.lore()) {
            String lower = line.toLowerCase(Locale.ROOT);
            for (String bad : options.unsellableLore()) {
                if (lower.contains(bad)) {
                    return Optional.empty();
                }
            }
        }
        Optional<PriceEntry> found = match(item);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        PriceEntry entry = found.get();
        if (options.blockCustomNames() && item.name() != null && entry.name() == null) {
            return Optional.empty();
        }
        if (entry.price().signum() <= 0) {
            return Optional.empty();
        }
        if (item.damage() > 0 && item.maxDamage() > 0 && options.requireFullDurability()) {
            return Optional.empty();
        }
        int work = options.scale() + 6;
        BigDecimal price = entry.price();
        if (options.devalueByDurability() && item.maxDamage() > 0 && item.damage() > 0) {
            int left = Math.max(0, item.maxDamage() - item.damage());
            price = price.multiply(BigDecimal.valueOf(left))
                    .divide(BigDecimal.valueOf(item.maxDamage()), work, RoundingMode.DOWN);
        }
        if (entry.enchantments() == null && entry.accept().contains(PriceEntry.Attribute.ENCHANTMENTS)) {
            for (Map.Entry<String, Integer> enchantment : item.enchantments().entrySet()) {
                BigDecimal perLevel = enchantmentValues.get(normaliseEnchantment(enchantment.getKey()));
                if (perLevel != null && perLevel.signum() > 0) {
                    price = price.add(perLevel.multiply(BigDecimal.valueOf(Math.max(0, enchantment.getValue()))));
                }
            }
        }
        if (!item.contents().isEmpty()) {
            if (options.containerMode() == ContainerMode.EMPTY_ONLY) {
                return Optional.empty();
            }
            for (ItemFacts.Content content : item.contents()) {
                if (content.amount() <= 0) {
                    continue;
                }
                Optional<BigDecimal> inner = unitPrice(content.facts(), depth + 1);
                if (inner.isEmpty()) {
                    // One unsellable item inside makes the whole container unsellable: never destroy it.
                    return Optional.empty();
                }
                price = price.add(inner.get().multiply(BigDecimal.valueOf(content.amount())));
            }
        }
        price = price.setScale(options.scale(), RoundingMode.DOWN);
        return price.signum() > 0 ? Optional.of(price) : Optional.empty();
    }
}
