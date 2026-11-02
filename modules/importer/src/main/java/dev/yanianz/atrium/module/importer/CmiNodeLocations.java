package dev.yanianz.atrium.module.importer;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.Locale;
import java.util.Optional;
import org.spongepowered.configurate.ConfigurationNode;

/** Reads a location from a YAML node: a CMI string, a map with world/x/y/z/yaw/pitch, or a list. */
final class CmiNodeLocations {
    private CmiNodeLocations() {}

    static Optional<StoredLocation> read(ConfigurationNode node) {
        if (node == null || node.virtual()) {
            return Optional.empty();
        }
        if (node.isList()) {
            var children = node.childrenList();
            if (children.size() >= 2 && children.get(1).isList()) {
                // [[world, x, y, z, yaw, pitch], ...]: the first stored location wins.
                return firstLocation(children.get(1));
            }
            if (children.size() >= 6) {
                String world = children.get(0).getString();
                Double x = number(children.get(1).getString());
                Double y = number(children.get(2).getString());
                Double z = number(children.get(3).getString());
                Double yaw = number(children.get(4).getString());
                Double pitch = number(children.get(5).getString());
                return build(world, x, y, z, yaw, pitch);
            }
            return Optional.empty();
        }
        if (node.isMap()) {
            String world = text(node, "world", "World");
            Double x = number(text(node, "x", "X"));
            Double y = number(text(node, "y", "Y"));
            Double z = number(text(node, "z", "Z"));
            Double yaw = number(text(node, "yaw", "Yaw"));
            Double pitch = number(text(node, "pitch", "Pitch"));
            if (world != null && x != null && y != null && z != null) {
                return build(world, x, y, z, yaw, pitch);
            }
            ConfigurationNode nested = child(node, "loc", "Loc", "location", "Location");
            if (!nested.virtual()) {
                return read(nested);
            }
        }
        return CmiLocations.parse(node.getString());
    }

    private static Optional<StoredLocation> firstLocation(ConfigurationNode list) {
        var values = list.childrenList();
        if (values.size() >= 6) {
            return build(values.get(0).getString(), number(values.get(1).getString()),
                    number(values.get(2).getString()), number(values.get(3).getString()),
                    number(values.get(4).getString()), number(values.get(5).getString()));
        }
        return Optional.empty();
    }

    private static Optional<StoredLocation> build(String world, Double x, Double y, Double z, Double yaw, Double pitch) {
        if (world == null || world.isBlank() || x == null || y == null || z == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new StoredLocation(world, x, y, z,
                    yaw == null ? 0f : yaw.floatValue(), pitch == null ? 0f : pitch.floatValue()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    static ConfigurationNode child(ConfigurationNode node, String... names) {
        for (String name : names) {
            ConfigurationNode direct = node.node(name);
            if (!direct.virtual()) {
                return direct;
            }
        }
        for (var entry : node.childrenMap().entrySet()) {
            String key = String.valueOf(entry.getKey());
            for (String name : names) {
                if (key.equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
        }
        return node.node(names[0]);
    }

    static String text(ConfigurationNode node, String... names) {
        ConfigurationNode value = child(node, names);
        return value.virtual() ? null : value.getString();
    }

    static boolean truthy(ConfigurationNode node, boolean fallback, String... names) {
        ConfigurationNode value = child(node, names);
        return value.virtual() ? fallback : value.getBoolean(fallback);
    }

    static Double number(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(value.trim().replace(',', '.'));
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String nameFromKey(Object key, ConfigurationNode value) {
        String name = key == null ? null : String.valueOf(key);
        if (name == null || name.isBlank() || name.equals("null")) {
            name = text(value, "Name", "name");
        }
        return name == null ? null : name.trim();
    }

    static String normalizeCategory(String value) {
        if (value == null || value.isBlank()) {
            return "general";
        }
        String trimmed = value.trim();
        return trimmed.length() > 32 ? trimmed.substring(0, 32) : trimmed;
    }

    static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
