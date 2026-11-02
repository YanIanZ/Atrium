package dev.yanianz.atrium.module.importer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Parses EssentialsX {@code jails.yml}: one location per jail under {@code jails:} (older files keep the
 * jails at the root). The world is a UUID with an optional {@code world-name}, or a name.
 */
final class EssentialsJailParser {
    private EssentialsJailParser() {}

    record ParsedJail(String name, EssentialsLocations.EssLocation location, List<String> notes, String problem) {
        boolean ok() {
            return problem == null;
        }
    }

    static List<ParsedJail> parse(ConfigurationNode root) {
        if (root == null || root.virtual()) {
            return List.of();
        }
        ConfigurationNode section = CmiNodeLocations.child(root, "jails", "Jails");
        ConfigurationNode source = section.virtual() ? root : section;
        List<ParsedJail> jails = new ArrayList<>();
        for (var entry : source.childrenMap().entrySet()) {
            if (!entry.getValue().isMap() && !entry.getValue().isList()) {
                continue;
            }
            String raw = String.valueOf(entry.getKey()).strip();
            List<String> notes = new ArrayList<>();
            String name = raw;
            if (!ImportNames.valid(name)) {
                name = ImportNames.sanitize(raw);
                if (name == null) {
                    jails.add(new ParsedJail(raw, null, List.of(), "invalid jail name '" + raw + "'"));
                    continue;
                }
                notes.add("renamed '" + raw + "' to '" + name + "'");
            }
            Optional<EssentialsLocations.EssLocation> location = EssentialsLocations.parse(entry.getValue());
            jails.add(location.isPresent()
                    ? new ParsedJail(name, location.get(), List.copyOf(notes), null)
                    : new ParsedJail(name, null, List.of(), "jail '" + raw + "' has no valid location"));
        }
        return List.copyOf(jails);
    }
}
