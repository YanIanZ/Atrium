package dev.yanianz.atrium.module.importer;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.ArrayList;
import java.util.List;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Parses CMI's {@code Saves/Warps.yml}. Every top-level section (or every entry of a {@code warps}
 * section) is one warp with a {@code Loc} and an optional {@code Category}. Unknown extra keys from CMI
 * are ignored.
 */
final class CmiWarpFileParser {
    private CmiWarpFileParser() {}

    record ParsedWarp(String name, String category, StoredLocation location, String problem) {
        boolean ok() {
            return problem == null;
        }
    }

    static List<ParsedWarp> parse(ConfigurationNode root) {
        if (root == null || root.virtual()) {
            return List.of();
        }
        List<ParsedWarp> warps = new ArrayList<>();
        ConfigurationNode section = CmiNodeLocations.child(root, "warps", "Warps");
        ConfigurationNode source = section.virtual() ? root : section;
        if (source.isList()) {
            for (ConfigurationNode entry : source.childrenList()) {
                warps.add(fromNode(CmiNodeLocations.nameFromKey(null, entry), entry));
            }
        } else {
            for (var entry : source.childrenMap().entrySet()) {
                warps.add(fromNode(CmiNodeLocations.nameFromKey(entry.getKey(), entry.getValue()), entry.getValue()));
            }
        }
        return warps;
    }

    private static ParsedWarp fromNode(String rawName, ConfigurationNode node) {
        if (rawName == null || rawName.isBlank()) {
            return new ParsedWarp(null, null, null, "warp without a name");
        }
        if (!rawName.matches("[A-Za-z0-9_-]{1,32}")) {
            return new ParsedWarp(rawName, null, null, "invalid warp name '" + rawName + "'");
        }
        String category = CmiNodeLocations.normalizeCategory(
                CmiNodeLocations.text(node, "Category", "category", "Cat", "cat"));
        if (!category.matches("[A-Za-z0-9_-]{1,32}")) {
            return new ParsedWarp(rawName, category, null, "invalid warp category '" + category + "'");
        }
        ConfigurationNode location = CmiNodeLocations.child(node, "Loc", "loc", "Location", "location");
        StoredLocation parsed = CmiNodeLocations.read(location).orElse(null);
        if (parsed == null) {
            return new ParsedWarp(rawName, category, null, "warp '" + rawName + "' has no valid location");
        }
        return new ParsedWarp(rawName, category, parsed, null);
    }
}
