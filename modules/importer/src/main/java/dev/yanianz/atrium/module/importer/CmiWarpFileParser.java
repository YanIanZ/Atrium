package dev.yanianz.atrium.module.importer;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Parses CMI's {@code Saves/Warps.yml}. Every top-level section (or every entry of a {@code warps}
 * section) is one warp:
 * <pre>
 * shop:
 *   Location: world;10.5;65.0;-3.5;90.0;0.0     # world;x;y;z;yaw;pitch (older files: Loc, world:x:y:z:pitch:yaw)
 *   Creator: 631e3896-da2a-4077-974b-d047859d76bc
 *   Item: !!binary H4sI...                      # icon, ItemStack#serializeAsBytes
 *   Category: town
 *   ReqPerm: true                               # per-warp permission required
 *   HideFromList: true / Page: 2 / Slots: 20    # menu layout, no Atrium equivalent
 * </pre>
 * Unknown keys are ignored; names Atrium cannot use are made valid and the rename is reported.
 */
final class CmiWarpFileParser {
    private CmiWarpFileParser() {}

    /**
     * @param permissionRequired {@code null} when the warp does not say (CMI's {@code Warps.requirePerm}
     *                           default applies)
     */
    record ParsedWarp(String name, String category, StoredLocation location, String problem, UUID owner,
            long createdAt, byte[] icon, Boolean permissionRequired, List<String> notes) {
        boolean ok() {
            return problem == null;
        }

        ParsedWarp(String name, String category, String problem) {
            this(name, category, null, problem, null, 0L, null, null, List.of());
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
                if (!entry.getValue().isMap()) {
                    continue; // a stray scalar is not a warp
                }
                warps.add(fromNode(CmiNodeLocations.nameFromKey(entry.getKey(), entry.getValue()), entry.getValue()));
            }
        }
        return warps;
    }

    private static ParsedWarp fromNode(String rawName, ConfigurationNode node) {
        if (rawName == null || rawName.isBlank()) {
            return new ParsedWarp(null, null, "warp without a name");
        }
        List<String> notes = new ArrayList<>();
        String name = rawName.strip();
        if (!ImportNames.valid(name)) {
            String fixed = ImportNames.sanitize(name);
            if (fixed == null) {
                return new ParsedWarp(rawName, null, "warp name '" + rawName + "' has no usable characters");
            }
            notes.add("renamed '" + rawName + "' to '" + fixed + "' (Atrium names allow A-Z, 0-9, _ and -)");
            name = fixed;
        }
        String rawCategory = CmiNodeLocations.text(node, "Category", "category", "Cat", "cat", "Group", "group");
        String category = CmiNodeLocations.normalizeCategory(rawCategory);
        if (!ImportNames.valid(category)) {
            String fixed = ImportNames.sanitize(category);
            notes.add("category '" + category + "' changed to '" + (fixed == null ? "general" : fixed) + "'");
            category = fixed == null ? "general" : fixed;
        }
        ConfigurationNode location = CmiNodeLocations.child(node, "Location", "location", "Loc", "loc");
        StoredLocation parsed = CmiNodeLocations.read(location).orElse(null);
        if (parsed == null) {
            return new ParsedWarp(name, category, "warp '" + rawName + "' has no valid location");
        }
        UUID owner = uuid(CmiNodeLocations.text(node, "Creator", "creator", "Owner", "owner"));
        long created = longValue(CmiNodeLocations.child(node, "CreationTime", "Created", "CreatedAt", "creationTime"));
        byte[] icon = binary(CmiNodeLocations.child(node, "Item", "item", "Icon", "icon"));
        Boolean reqPerm = null;
        ConfigurationNode perm = CmiNodeLocations.child(node, "ReqPerm", "RequirePerm", "RequirePermission", "reqperm");
        if (!perm.virtual()) {
            reqPerm = perm.getBoolean(false);
        }
        if (CmiNodeLocations.truthy(node, false, "HideFromList")) {
            notes.add("hidden from CMI's warp list; Atrium lists every warp the player may use");
        }
        return new ParsedWarp(name, category, parsed, null, owner, created, icon, reqPerm, List.copyOf(notes));
    }

    static UUID uuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static long longValue(ConfigurationNode node) {
        if (node == null || node.virtual()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(String.valueOf(node.raw()).trim()));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** A YAML {@code !!binary} value (or a base64 string) as bytes; {@code null} when absent or malformed. */
    static byte[] binary(ConfigurationNode node) {
        if (node == null || node.virtual()) {
            return null;
        }
        Object raw = node.raw();
        if (raw instanceof byte[] bytes) {
            return bytes.length == 0 ? null : bytes.clone();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Base64.getMimeDecoder().decode(text.trim());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }
}
