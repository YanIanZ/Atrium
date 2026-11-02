package dev.yanianz.atrium.module.importer;

import java.util.ArrayList;
import java.util.List;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Parses EssentialsX kits from {@code kits.yml} (and the legacy {@code kits:} section of
 * {@code config.yml}):
 * <pre>
 * kits:
 *   tools:
 *     delay: 600          # seconds; -1 = once per player
 *     items:
 *       - stone_pickaxe 1 efficiency:2
 *       - /say {player} got tools
 * </pre>
 * The cost of a kit lives in {@code config.yml} under {@code command-costs: kit-<name>} and is read by
 * the import job. The permission is {@code essentials.kits.<name>}; Atrium uses {@code atrium.kit.<name>}.
 */
final class EssentialsKitsParser {
    private EssentialsKitsParser() {}

    /**
     * @param items        number of item lines (commands included)
     * @param delaySeconds cooldown; 0 when the kit is one-time ({@code oneTime})
     * @param specs        the parsed items
     * @param commands     command and money lines Atrium kits cannot run
     */
    record ParsedKit(String name, int items, long delaySeconds, String money, String problem, boolean oneTime,
            List<KitItemSpec> specs, List<String> commands, String sourceName) {
        ParsedKit(String name, int items, long delaySeconds, String money, String problem) {
            this(name, items, delaySeconds, money, problem, false, List.of(), List.of(), name);
        }

        boolean ok() {
            return problem == null;
        }
    }

    static List<ParsedKit> parse(ConfigurationNode root) {
        return parse(root, "$");
    }

    /** @param currencySymbol Essentials' {@code currency-symbol}: lines starting with it (or {@code $}) give money */
    static List<ParsedKit> parse(ConfigurationNode root, String currencySymbol) {
        if (root == null || root.virtual()) {
            return List.of();
        }
        ConfigurationNode section = CmiNodeLocations.child(root, "kits", "Kits");
        return section.virtual() || !section.isMap() ? List.of() : parseSection(section, currencySymbol);
    }

    private static List<ParsedKit> parseSection(ConfigurationNode section, String currencySymbol) {
        List<ParsedKit> kits = new ArrayList<>();
        for (var entry : section.childrenMap().entrySet()) {
            kits.add(parseOne(String.valueOf(entry.getKey()), entry.getValue(), currencySymbol));
        }
        return List.copyOf(kits);
    }

    private static ParsedKit parseOne(String rawName, ConfigurationNode node, String currencySymbol) {
        String source = rawName == null ? null : rawName.trim();
        if (source == null || source.isBlank()) {
            return new ParsedKit(null, 0, 0, null, "kit without a name");
        }
        String name = ImportNames.valid(source) ? source : ImportNames.sanitize(source);
        if (name == null) {
            return new ParsedKit(source, 0, 0, null, "invalid kit name '" + source + "'");
        }
        if (!node.isMap()) {
            return new ParsedKit(name, 0, 0, null, "kit '" + name + "' is not a section");
        }
        ConfigurationNode items = CmiNodeLocations.child(node, "items", "Items");
        List<String> lines = new ArrayList<>();
        if (items.isList()) {
            for (ConfigurationNode item : items.childrenList()) {
                String text = item.getString();
                if (text != null) {
                    lines.add(text);
                }
            }
        } else if (items.isMap()) {
            for (ConfigurationNode item : items.childrenMap().values()) {
                String text = item.getString();
                if (text != null) {
                    lines.add(text);
                }
            }
        }
        List<KitItemSpec> specs = new ArrayList<>();
        List<String> commands = new ArrayList<>();
        for (String line : lines) {
            EssentialsItemParser.Line parsed = EssentialsItemParser.parse(line, -1, currencySymbol);
            if (parsed.kind() == EssentialsItemParser.Kind.ITEM) {
                specs.add(parsed.item());
            } else {
                commands.add(parsed.text());
            }
        }
        long delay = readLong(CmiNodeLocations.child(node, "delay", "cooldown", "Delay"));
        boolean oneTime = delay < 0;
        String money = CmiNodeLocations.text(node, "money", "cost", "Cost");
        return new ParsedKit(name, lines.size(), Math.max(0L, delay), money, null, oneTime, List.copyOf(specs),
                List.copyOf(commands), source);
    }

    /** Essentials reads the delay as a decimal number of seconds ({@code 1.5}); a part second rounds up. */
    static long readLong(ConfigurationNode node) {
        if (node == null || node.virtual()) {
            return 0L;
        }
        try {
            double value = Double.parseDouble(String.valueOf(node.raw()).trim());
            if (!Double.isFinite(value)) {
                return 0L;
            }
            return value < 0 ? -1L : (long) Math.ceil(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
