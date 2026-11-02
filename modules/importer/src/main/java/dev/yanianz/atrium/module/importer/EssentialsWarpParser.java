package dev.yanianz.atrium.module.importer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Parses one file from {@code plugins/Essentials/warps/}. EssentialsX writes one warp per file, but a
 * combined {@code warps:} section is accepted as well, so a hand-merged file still imports.
 */
final class EssentialsWarpParser {
    private EssentialsWarpParser() {}

    /** {@code owner} is Essentials' {@code lastowner}; {@code notes} lists renames. */
    record ParsedWarp(String name, String category, EssentialsLocations.EssLocation location, String problem,
            java.util.UUID owner, List<String> notes) {
        ParsedWarp(String name, String category, EssentialsLocations.EssLocation location, String problem) {
            this(name, category, location, problem, null, List.of());
        }

        boolean ok() {
            return problem == null;
        }
    }

    static List<ParsedWarp> parse(String fileName, ConfigurationNode root) {
        if (root == null || root.virtual()) {
            return List.of();
        }
        ConfigurationNode section = CmiNodeLocations.child(root, "warps", "Warps");
        if (!section.virtual()) {
            return parseSection(section);
        }
        if (looksLikeSection(root)) {
            return parseSection(root);
        }
        return List.of(parseOne(base(fileName), root));
    }

    private static boolean looksLikeSection(ConfigurationNode root) {
        if (CmiNodeLocations.child(root, "world", "World", "world-name").virtual()) {
            return false;
        }
        for (var entry : root.childrenMap().entrySet()) {
            if (!entry.getValue().isMap()) {
                return false;
            }
            if (CmiNodeLocations.child(entry.getValue(), "x", "X").virtual()) {
                return false;
            }
        }
        return !root.childrenMap().isEmpty();
    }

    private static String base(String fileName) {
        String name = fileName == null ? "" : fileName;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".yml")) {
            return name.substring(0, name.length() - 4);
        }
        if (lower.endsWith(".yaml")) {
            return name.substring(0, name.length() - 5);
        }
        return name;
    }

    private static List<ParsedWarp> parseSection(ConfigurationNode section) {
        List<ParsedWarp> warps = new ArrayList<>();
        if (section.isList()) {
            for (ConfigurationNode entry : section.childrenList()) {
                warps.add(parseOne(null, entry));
            }
            return List.copyOf(warps);
        }
        for (var entry : section.childrenMap().entrySet()) {
            warps.add(parseOne(String.valueOf(entry.getKey()), entry.getValue()));
        }
        return List.copyOf(warps);
    }

    private static ParsedWarp parseOne(String fallbackName, ConfigurationNode node) {
        String name = CmiNodeLocations.text(node, "name", "Name");
        if (name == null || name.isBlank()) {
            name = fallbackName;
        }
        name = name == null ? null : name.trim();
        if (name == null || name.isBlank()) {
            return new ParsedWarp(null, null, null, "warp without a name");
        }
        List<String> notes = new ArrayList<>();
        if (!ImportNames.valid(name)) {
            String fixed = ImportNames.sanitize(name);
            if (fixed == null) {
                return new ParsedWarp(name, null, null, "invalid warp name '" + name + "'");
            }
            notes.add("renamed '" + name + "' to '" + fixed + "' (Atrium names allow A-Z, 0-9, _ and -)");
            name = fixed;
        }
        String category = CmiNodeLocations.text(node, "category", "Category");
        if (category == null || category.isBlank()) {
            category = "general";
        }
        category = category.trim();
        if (!ImportNames.valid(category)) {
            return new ParsedWarp(name, null, null, "invalid warp category '" + category + "'");
        }
        Optional<EssentialsLocations.EssLocation> location = EssentialsLocations.parse(node);
        if (location.isEmpty()) {
            return new ParsedWarp(name, category, null, "warp '" + name + "' has no valid location");
        }
        java.util.UUID owner = CmiWarpFileParser.uuid(CmiNodeLocations.text(node, "lastowner", "lastOwner", "owner"));
        return new ParsedWarp(name, category.toLowerCase(Locale.ROOT), location.get(), null, owner, List.copyOf(notes));
    }
}
