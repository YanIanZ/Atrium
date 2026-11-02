package dev.yanianz.atrium.module.importer;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.ArrayList;
import java.util.List;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Parses CMI's {@code Saves/Holograms.yml}. Each section is one hologram: {@code Loc}, {@code Lines} (a
 * literal {@code !nextpage!} line starts the next page), {@code Enabled}, and many optional render
 * settings that Atrium does not import. The importer only takes the name, location and text.
 */
final class CmiHologramFileParser {
    static final String PAGE_SEPARATOR = "!nextpage!";

    private CmiHologramFileParser() {}

    record ParsedHologram(String name, StoredLocation location, List<List<String>> pages, boolean enabled, String problem) {
        boolean ok() {
            return problem == null;
        }
    }

    static List<ParsedHologram> parse(ConfigurationNode root) {
        if (root == null || root.virtual()) {
            return List.of();
        }
        List<ParsedHologram> result = new ArrayList<>();
        for (var entry : root.childrenMap().entrySet()) {
            String name = CmiNodeLocations.nameFromKey(entry.getKey(), entry.getValue());
            result.add(fromNode(name, entry.getValue()));
        }
        return result;
    }

    private static ParsedHologram fromNode(String rawName, ConfigurationNode node) {
        if (rawName == null || rawName.isBlank()) {
            return new ParsedHologram(null, null, List.of(), false, "hologram without a name");
        }
        String name = rawName.replace('.', '_');
        if (!name.matches("[A-Za-z0-9_-]{1,32}")) {
            return new ParsedHologram(name, null, List.of(), false, "invalid hologram name '" + name + "'");
        }
        StoredLocation location = CmiNodeLocations.read(
                CmiNodeLocations.child(node, "Loc", "loc", "Location", "location")).orElse(null);
        if (location == null) {
            return new ParsedHologram(name, null, List.of(), false, "hologram '" + name + "' has no valid location");
        }
        boolean enabled = CmiNodeLocations.truthy(node, true, "Enabled", "enabled");
        ConfigurationNode linesNode = CmiNodeLocations.child(node, "Lines", "lines", "Text", "text");
        List<List<String>> pages = splitPages(linesNode);
        return new ParsedHologram(name, location, pages, enabled, null);
    }

    private static List<List<String>> splitPages(ConfigurationNode linesNode) {
        List<List<String>> pages = new ArrayList<>();
        List<String> current = new ArrayList<>();
        if (!linesNode.virtual()) {
            if (linesNode.isList()) {
                for (ConfigurationNode line : linesNode.childrenList()) {
                    addLine(pages, current, line.getString(""));
                }
            } else {
                addLine(pages, current, linesNode.getString(""));
            }
        }
        pages.add(List.copyOf(current));
        while (pages.size() > 1 && pages.getLast().isEmpty()) {
            pages.removeLast();
        }
        return pages;
    }

    private static void addLine(List<List<String>> pages, List<String> current, String text) {
        if (text == null) {
            return;
        }
        if (text.equals(PAGE_SEPARATOR)) {
            pages.add(List.copyOf(current));
            current.clear();
        } else if (!text.isEmpty()) {
            current.add(LegacyText.toMiniMessage(text));
        }
    }
}
