package dev.yanianz.atrium.module.importer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Parses one CMI kit file ({@code plugins/CMI/Kits/*.yml}). Every top-level section is a kit:
 * <pre>
 * Starter:
 *   Delay: 3600              # seconds
 *   MoneyCost: 100
 *   MaxUsages: 5
 *   Items:
 *     '1': !!binary H4sI...  # full item, ItemStack#serializeAsBytes (current CMI)
 *     '2':                   # Bukkit item map (older CMI)
 *       type: STONE
 *       amount: 16
 *       meta: {display-name: ..., lore: [...], enchants: {SHARPNESS: 2}}
 *   Icon: !!binary H4sI...
 *   Commands: [...]          # not carried over
 *   ExpCost / Conditions / Group / Weight / CommandName   # not carried over (reported)
 * </pre>
 * The permission {@code cmi.kit.<name>} becomes Atrium's {@code atrium.kit.<name>}.
 */
final class CmiKitParser {
    private static final Set<String> ITEM_KEYS = Set.of("v", "type", "amount", "meta", "==");
    private static final Set<String> META_KEYS = Set.of("==", "meta-type", "display-name", "lore", "enchants");

    private CmiKitParser() {}

    record ParsedKit(String name, String sourceName, long delaySeconds, BigDecimal cost, int maxUses,
            List<KitItemSpec> items, byte[] icon, List<String> notes, String problem) {
        boolean ok() {
            return problem == null;
        }
    }

    static List<ParsedKit> parse(ConfigurationNode root) {
        if (root == null || root.virtual()) {
            return List.of();
        }
        List<ParsedKit> kits = new ArrayList<>();
        for (var entry : root.childrenMap().entrySet()) {
            if (entry.getValue().isMap()) {
                kits.add(parseOne(String.valueOf(entry.getKey()).strip(), entry.getValue()));
            }
        }
        return List.copyOf(kits);
    }

    private static ParsedKit parseOne(String source, ConfigurationNode node) {
        List<String> notes = new ArrayList<>();
        String lower = source.toLowerCase(Locale.ROOT);
        String name = ImportNames.valid(lower) ? lower : ImportNames.lower(ImportNames.sanitize(lower));
        if (name == null) {
            return new ParsedKit(source, source, 0, BigDecimal.ZERO, -1, List.of(), null, List.of(),
                    "invalid kit name '" + source + "'");
        }
        long delay = Math.max(0L, CmiWarpFileParser.longValue(CmiNodeLocations.child(node, "Delay", "delay")));
        BigDecimal cost = BigDecimal.ZERO;
        String rawCost = CmiNodeLocations.text(node, "MoneyCost", "moneyCost", "Cost");
        if (rawCost != null && !rawCost.isBlank()) {
            try {
                cost = new BigDecimal(rawCost.trim());
                if (cost.signum() < 0) {
                    notes.add("negative MoneyCost ignored");
                    cost = BigDecimal.ZERO;
                }
            } catch (NumberFormatException e) {
                notes.add("MoneyCost '" + rawCost + "' is not a number; imported as free");
            }
        }
        int maxUses = -1;
        ConfigurationNode usages = CmiNodeLocations.child(node, "MaxUsages", "maxUsages", "Usages");
        if (!usages.virtual()) {
            long value = CmiWarpFileParser.longValue(usages);
            maxUses = value > 0 ? (int) Math.min(Integer.MAX_VALUE, value) : -1;
        }
        if (!CmiNodeLocations.truthy(node, true, "Enabled")) {
            notes.add("disabled in CMI; imported anyway (delete it with /kit delete if unwanted)");
        }
        for (String key : List.of("ExpCost", "Commands", "Conditions", "Group", "Weight", "CommandName")) {
            ConfigurationNode value = CmiNodeLocations.child(node, key);
            boolean present = !value.virtual() && !(value.isList() && value.childrenList().isEmpty())
                    && !(key.equals("CommandName") && source.equalsIgnoreCase(value.getString("")));
            if (present) {
                notes.add(key + " is not supported by Atrium kits");
            }
        }
        List<KitItemSpec> items = new ArrayList<>();
        ConfigurationNode itemNode = CmiNodeLocations.child(node, "Items", "items");
        if (itemNode.isMap()) {
            Map<Integer, ConfigurationNode> bySlot = new LinkedHashMap<>();
            boolean zeroBased = false;
            for (var entry : itemNode.childrenMap().entrySet()) {
                try {
                    int slot = Integer.parseInt(String.valueOf(entry.getKey()).trim());
                    zeroBased |= slot == 0;
                    bySlot.put(slot, entry.getValue());
                } catch (NumberFormatException e) {
                    bySlot.put(-1 - bySlot.size(), entry.getValue());
                }
            }
            for (var entry : bySlot.entrySet()) {
                int slot = entry.getKey() < 0 ? -1 : (zeroBased ? entry.getKey() : entry.getKey() - 1);
                items.add(item(slot >= 0 && slot <= 40 ? slot : -1, entry.getValue()));
            }
        } else if (itemNode.isList()) {
            for (ConfigurationNode entry : itemNode.childrenList()) {
                items.add(item(-1, entry));
            }
        }
        byte[] icon = CmiWarpFileParser.binary(CmiNodeLocations.child(node, "Icon", "icon"));
        return new ParsedKit(name, source, delay, cost, maxUses, List.copyOf(items), icon, List.copyOf(notes), null);
    }

    static KitItemSpec item(int slot, ConfigurationNode node) {
        byte[] bytes = CmiWarpFileParser.binary(node);
        if (bytes != null && !node.isMap()) {
            return KitItemSpec.serialized(slot, bytes);
        }
        if (!node.isMap()) {
            return KitItemSpec.broken(slot, "unreadable item entry");
        }
        String type = CmiNodeLocations.text(node, "type", "Type", "material");
        if (type == null || type.isBlank()) {
            return KitItemSpec.broken(slot, "item without a type");
        }
        int amount = 1;
        Double rawAmount = CmiNodeLocations.number(CmiNodeLocations.text(node, "amount", "Amount"));
        if (rawAmount != null) {
            amount = (int) Math.max(1, Math.min(99, rawAmount));
        }
        List<String> unsupported = new ArrayList<>();
        for (Object key : node.childrenMap().keySet()) {
            String k = String.valueOf(key);
            if (!ITEM_KEYS.contains(k)) {
                unsupported.add(k);
            }
        }
        String name = null;
        List<String> lore = new ArrayList<>();
        Map<String, Integer> enchants = new LinkedHashMap<>();
        ConfigurationNode meta = CmiNodeLocations.child(node, "meta");
        if (meta.isMap()) {
            name = CmiNodeLocations.text(meta, "display-name");
            ConfigurationNode loreNode = CmiNodeLocations.child(meta, "lore");
            for (ConfigurationNode line : loreNode.childrenList()) {
                lore.add(line.getString(""));
            }
            for (var e : CmiNodeLocations.child(meta, "enchants").childrenMap().entrySet()) {
                Double level = CmiNodeLocations.number(e.getValue().getString());
                if (level != null && level > 0) {
                    enchants.put(String.valueOf(e.getKey()), level.intValue());
                }
            }
            for (Object key : meta.childrenMap().keySet()) {
                if (!META_KEYS.contains(String.valueOf(key))) {
                    unsupported.add("meta." + key);
                }
            }
        }
        return new KitItemSpec(slot, type.trim().toLowerCase(Locale.ROOT), amount, name, lore, enchants, null,
                unsupported, null);
    }
}
