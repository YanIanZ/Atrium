package dev.yanianz.atrium.module.importer;

import java.util.ArrayList;
import java.util.List;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Parses EssentialsX kits from {@code kits.yml} (and the legacy {@code kits:} section of
 * {@code config.yml}). Only names and item counts are read: the kits module has no public write API, so
 * kits are reported for an admin to recreate with {@code /kit create}.
 */
final class EssentialsKitsParser {
    private EssentialsKitsParser() {}

    record ParsedKit(String name, int items, long delaySeconds, String money, String problem) {
        boolean ok() {
            return problem == null;
        }
    }

    static List<ParsedKit> parse(ConfigurationNode root) {
        if (root == null || root.virtual()) {
            return List.of();
        }
        ConfigurationNode section = CmiNodeLocations.child(root, "kits", "Kits");
        return section.virtual() || !section.isMap() ? List.of() : parseSection(section);
    }

    private static List<ParsedKit> parseSection(ConfigurationNode section) {
        List<ParsedKit> kits = new ArrayList<>();
        for (var entry : section.childrenMap().entrySet()) {
            kits.add(parseOne(String.valueOf(entry.getKey()), entry.getValue()));
        }
        return List.copyOf(kits);
    }

    private static ParsedKit parseOne(String rawName, ConfigurationNode node) {
        String name = rawName == null ? null : rawName.trim();
        if (name == null || name.isBlank()) {
            return new ParsedKit(null, 0, 0, null, "kit without a name");
        }
        if (!name.matches("[A-Za-z0-9_-]{1,32}")) {
            return new ParsedKit(name, 0, 0, null, "invalid kit name '" + name + "'");
        }
        if (!node.isMap()) {
            return new ParsedKit(name, 0, 0, null, "kit '" + name + "' is not a section");
        }
        ConfigurationNode items = CmiNodeLocations.child(node, "items", "Items");
        int count = items.virtual() ? 0
                : items.isList() ? items.childrenList().size() : items.isMap() ? items.childrenMap().size() : 0;
        long delay = readLong(CmiNodeLocations.child(node, "delay", "cooldown", "Delay"));
        String money = CmiNodeLocations.text(node, "money", "cost", "Cost");
        return new ParsedKit(name, count, delay, money, null);
    }

    private static long readLong(ConfigurationNode node) {
        if (node == null || node.virtual()) {
            return 0L;
        }
        try {
            return Math.max(0L, node.getLong(0L));
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
