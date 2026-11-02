package dev.yanianz.atrium.module.worth;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

/**
 * Reads and writes the price data file ({@code plugins/Atrium/data/worth/worth.yml}).
 *
 * <pre>
 * items:
 *   diamond: 100                 # plain entry: id = material, matches only vanilla diamonds
 *   ruby:                        # detailed entry
 *     material: emerald
 *     price: 250
 *     custom-model-data: [1001]  # exact tokens: numbers, s:text, b:true, c:#rrggbb
 *     item-model: myplugin:ruby
 *     name: Ruby                 # exact plain-text custom name
 *     enchantments: {unbreaking: 1}
 *     accept: [lore]             # attributes allowed to have any value
 * enchantment-values:
 *   sharpness: 5                 # added per level for entries that accept enchantments
 * </pre>
 */
public final class WorthFile {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]{1,64}");

    public record Parsed(List<PriceEntry> entries, Map<String, BigDecimal> enchantmentValues, List<String> warnings) {}

    private WorthFile() {}

    public static Parsed parse(ConfigurationNode root, Predicate<String> materialExists) {
        List<PriceEntry> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, BigDecimal> enchantments = new LinkedHashMap<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> child : root.node("items").childrenMap().entrySet()) {
            String id = String.valueOf(child.getKey()).toLowerCase(Locale.ROOT);
            try {
                PriceEntry entry = entry(id, child.getValue());
                if (!materialExists.test(entry.material())) {
                    warnings.add("items." + id + ": unknown material '" + entry.material() + "'");
                    continue;
                }
                entries.add(entry);
            } catch (IllegalArgumentException | SerializationException e) {
                warnings.add("items." + id + ": " + e.getMessage());
            }
        }
        for (Map.Entry<Object, ? extends ConfigurationNode> child : root.node("enchantment-values").childrenMap().entrySet()) {
            String key = PriceTable.normaliseEnchantment(String.valueOf(child.getKey()));
            try {
                enchantments.put(key, price(child.getValue().getString()));
            } catch (IllegalArgumentException e) {
                warnings.add("enchantment-values." + key + ": " + e.getMessage());
            }
        }
        return new Parsed(List.copyOf(entries), enchantments, List.copyOf(warnings));
    }

    private static PriceEntry entry(String id, ConfigurationNode node) throws SerializationException {
        if (!node.isMap()) {
            return new PriceEntry(id, id, price(node.getString()), null, null, null, null, Set.of());
        }
        String material = node.node("material").getString(id).toLowerCase(Locale.ROOT);
        BigDecimal price = price(node.node("price").getString());
        String itemModel = blankToNull(node.node("item-model").getString());
        List<String> cmd = null;
        ConfigurationNode cmdNode = node.node("custom-model-data");
        if (!cmdNode.virtual()) {
            cmd = new ArrayList<>();
            if (cmdNode.isList()) {
                for (ConfigurationNode token : cmdNode.childrenList()) {
                    cmd.add(ItemFacts.normaliseToken(token.getString("")));
                }
            } else {
                cmd.add(ItemFacts.normaliseToken(cmdNode.getString("")));
            }
        }
        String name = node.node("name").virtual() ? null : node.node("name").getString("");
        Map<String, Integer> enchantments = null;
        ConfigurationNode enchNode = node.node("enchantments");
        if (!enchNode.virtual()) {
            enchantments = new LinkedHashMap<>();
            for (Map.Entry<Object, ? extends ConfigurationNode> e : enchNode.childrenMap().entrySet()) {
                int level = e.getValue().getInt(-1);
                if (level < 1) {
                    throw new IllegalArgumentException("enchantment level must be >= 1");
                }
                enchantments.put(PriceTable.normaliseEnchantment(String.valueOf(e.getKey())), level);
            }
        }
        Set<PriceEntry.Attribute> accept = EnumSet.noneOf(PriceEntry.Attribute.class);
        for (String key : node.node("accept").getList(String.class, List.of())) {
            accept.add(PriceEntry.Attribute.byKey(key)
                    .orElseThrow(() -> new IllegalArgumentException("unknown accept attribute '" + key + "'")));
        }
        return new PriceEntry(id, material, price, itemModel, cmd, name, enchantments, accept);
    }

    static BigDecimal price(@Nullable String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("missing price");
        }
        BigDecimal value;
        try {
            value = new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid price '" + text + "'");
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException("negative price");
        }
        return value;
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Writes one entry under {@code items.<id>}, plain entries as a bare number. */
    public static void write(ConfigurationNode root, PriceEntry entry) throws SerializationException {
        ConfigurationNode node = root.node("items", entry.id());
        node.raw(null);
        if (entry.isPlain() && entry.id().equals(entry.material())) {
            node.set(number(entry.price()));
            return;
        }
        if (!entry.id().equals(entry.material())) {
            node.node("material").set(entry.material());
        }
        node.node("price").set(number(entry.price()));
        if (entry.itemModel() != null) {
            node.node("item-model").set(entry.itemModel());
        }
        if (entry.customModelData() != null) {
            node.node("custom-model-data").setList(String.class, entry.customModelData());
        }
        if (entry.name() != null) {
            node.node("name").set(entry.name());
        }
        if (entry.enchantments() != null) {
            ConfigurationNode e = node.node("enchantments");
            e.raw(new LinkedHashMap<>());
            for (Map.Entry<String, Integer> en : entry.enchantments().entrySet()) {
                e.node(en.getKey()).set(en.getValue());
            }
        }
        if (!entry.accept().isEmpty()) {
            node.node("accept").setList(String.class, entry.accept().stream().sorted().map(PriceEntry.Attribute::key).toList());
        }
    }

    public static void remove(ConfigurationNode root, String id) {
        root.node("items").removeChild(id);
    }

    private static Object number(BigDecimal price) {
        BigDecimal stripped = price.stripTrailingZeros();
        if (stripped.scale() <= 0 && stripped.abs().compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) < 0) {
            return stripped.longValueExact();
        }
        return stripped.toPlainString();
    }

    /** A free id for a new detailed entry: {@code <material>-<n>}. */
    public static String freeId(List<PriceEntry> existing, String material) {
        Set<String> used = new java.util.HashSet<>();
        existing.forEach(e -> used.add(e.id()));
        for (int i = 1; ; i++) {
            String id = material + "-" + i;
            if (!used.contains(id)) {
                return id;
            }
        }
    }

    public static boolean validId(String id) {
        return ID.matcher(id).matches();
    }
}
