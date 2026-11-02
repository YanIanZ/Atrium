package dev.yanianz.atrium.module.worth;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Reads the shop files of EconomyShopGUI ({@code shops/*.yml}, items priced with {@code sell}) and
 * ShopGUIPlus ({@code shops/*.yml}, items priced with {@code sellPrice}).
 *
 * <p>Only line items whose item meta Atrium can match are imported: material, optional custom model data and
 * an exact custom name. Any other identity data (lore, enchantments, potion data, spawners, NBT, ...) is
 * reported instead of being turned into a price that would pay for a different item.
 *
 * <p>ShopGUIPlus quotes the price of the configured {@code quantity}; Atrium prices one unit, so those prices
 * are divided by the quantity. EconomyShopGUI prices are per item.
 */
final class ShopFileImporter implements PriceImporter {
    enum Format { ECONOMYSHOPGUI, SHOPGUIPLUS }

    /** EconomyShopGUI keys that change the sold item; a priced line carrying any of them is reported. */
    private static final Set<String> ESG_IDENTITY_META = Set.of(
            "lore", "enchantments", "potiontypes", "spawnertype", "skullowner", "skull-texture", "armorcolor",
            "duration", "colors", "fade-colors", "flicker", "trail", "shape", "instrument", "stew-effect",
            "armor-trim", "nbtdata", "components", "recipes", "spawner", "mob");

    /** ShopGUIPlus keys at the item level that do not change the identity of the sold item. */
    private static final Set<String> SGP_ITEM_ALLOWED = Set.of(
            "material", "quantity", "name", "damage", "custom-model-data", "custommodeldata",
            "type", "slot", "page", "buyprice", "sellprice", "buy", "sell", "buycommand", "sellcommand");

    private final PriceImportSource source;
    private final Format format;

    ShopFileImporter(PriceImportSource source, Format format) {
        this.source = source;
        this.format = format;
    }

    @Override
    public PriceImportSource source() {
        return source;
    }

    @Override
    public ImportParse parse(Map<String, String> files) {
        ImportParse.Builder out = new ImportParse.Builder();
        List<String> names = new ArrayList<>(files.keySet());
        names.sort(String::compareTo);
        for (String name : names) {
            ConfigurationNode root;
            try {
                root = ImportParse.yaml(files.get(name));
            } catch (Exception e) {
                out.skip(name, "invalid-yaml", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                continue;
            }
            walk(root, name, "", out);
        }
        return out.build();
    }

    private void walk(ConfigurationNode node, String file, String path, ImportParse.Builder out) {
        for (Map.Entry<Object, ? extends ConfigurationNode> entry : node.childrenMap().entrySet()) {
            ConfigurationNode child = entry.getValue();
            if (!child.isMap()) {
                continue;
            }
            String key = String.valueOf(entry.getKey());
            String childPath = path.isEmpty() ? key : path + "." + key;
            if (looksLikeItem(child)) {
                item(child, file, childPath, out);
                continue;
            }
            walk(child, file, childPath, out);
        }
    }

    private boolean looksLikeItem(ConfigurationNode node) {
        if (sellNode(node).isEmpty()) {
            return false;
        }
        return !node.node("material").virtual() || !node.node("item", "material").virtual();
    }

    private Optional<ConfigurationNode> sellNode(ConfigurationNode node) {
        List<String> keys = format == Format.ECONOMYSHOPGUI
                ? List.of("sell", "sell-price", "sellPrice")
                : List.of("sellPrice", "sell-price", "sell");
        for (String key : keys) {
            ConfigurationNode value = node.node(key);
            if (!value.virtual()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private void item(ConfigurationNode node, String file, String path, ImportParse.Builder out) {
        String origin = file + " " + path;
        ConfigurationNode type = node.node("type");
        if (!type.virtual() && !"item".equalsIgnoreCase(type.getString("item"))) {
            // Permission, command, enchantment and dummy entries are not items.
            return;
        }
        ConfigurationNode itemNode = node.node("item").isMap() ? node.node("item") : node;
        Optional<String> material = ImportText.material(itemNode.node("material").getString());
        if (material.isEmpty()) {
            out.skip(origin, "custom-item",
                    String.valueOf(itemNode.node("material").getString("(missing material)")));
            return;
        }
        Optional<BigDecimal> total = ImportParse.price(sellNode(node).orElse(node.node("sell")));
        if (total.isEmpty()) {
            out.skip(origin, "invalid-price", String.valueOf(sellNode(node).map(n -> n.getString("?"))
                    .orElse("(missing)")));
            return;
        }
        if (total.get().signum() <= 0) {
            out.skip(origin, "not-sellable", material.get() + " sell " + total.get().toPlainString());
            return;
        }
        String meta = unsupportedMeta(node, itemNode);
        if (meta != null) {
            out.skip(origin, "item-meta", meta);
            return;
        }
        int amount = format == Format.SHOPGUIPLUS ? Math.max(1, itemNode.node("quantity").getInt(1)) : 1;
        Optional<BigDecimal> unit = EnginePrice.unit(total.get(), amount);
        if (unit.isEmpty()) {
            out.skip(origin, "invalid-price", "unit price rounds to 0");
            return;
        }
        out.candidate(new ImportCandidate(origin, material.get(), customModelData(itemNode),
                ImportText.cleanName(itemNode.node("name").getString()), unit.get(), total.get(), amount));
    }

    /** The first identity attribute Atrium cannot reproduce, or {@code null} when the item maps cleanly. */
    private String unsupportedMeta(ConfigurationNode node, ConfigurationNode itemNode) {
        if (format == Format.ECONOMYSHOPGUI) {
            Set<String> found = new LinkedHashSet<>();
            for (Map.Entry<Object, ? extends ConfigurationNode> child : node.childrenMap().entrySet()) {
                String key = String.valueOf(child.getKey()).toLowerCase(Locale.ROOT);
                if (ESG_IDENTITY_META.contains(key) && !isEmpty(child.getValue())) {
                    found.add(String.valueOf(child.getKey()));
                }
            }
            return found.isEmpty() ? null : String.join(", ", found);
        }
        List<String> found = new ArrayList<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> child : itemNode.childrenMap().entrySet()) {
            String key = String.valueOf(child.getKey());
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.equals("custommodeldata")) {
                continue;
            }
            if (!SGP_ITEM_ALLOWED.contains(lower) && !isEmpty(child.getValue())) {
                found.add(key);
            }
        }
        if (itemNode.node("damage").getInt(0) > 0) {
            found.add("damage");
        }
        return found.isEmpty() ? null : String.join(", ", found);
    }

    private static boolean isEmpty(ConfigurationNode node) {
        if (node.isMap() || node.isList()) {
            return node.empty();
        }
        String text = node.getString();
        return text == null || text.isBlank() || "false".equalsIgnoreCase(text) || "0".equals(text);
    }

    private static List<String> customModelData(ConfigurationNode itemNode) {
        for (String key : List.of("custom-model-data", "CustomModelData", "customModelData")) {
            ConfigurationNode node = itemNode.node(key);
            if (node.virtual()) {
                continue;
            }
            List<String> tokens = new ArrayList<>();
            if (node.isList()) {
                for (ConfigurationNode token : node.childrenList()) {
                    String value = token.getString("");
                    if (!value.isBlank()) {
                        tokens.add(ItemFacts.normaliseToken(value));
                    }
                }
            } else {
                String value = node.getString("");
                if (!value.isBlank()) {
                    tokens.add(ItemFacts.normaliseToken(value));
                }
            }
            return tokens;
        }
        return null;
    }
}
