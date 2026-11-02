package dev.yanianz.atrium.module.importer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Item names as EssentialsX kits and commands write them. Kit lines use EssentialsX's own aliases
 * ({@code stonesword}, {@code dpickaxe}, {@code lhelm}), which are defined in the source server's
 * {@code plugins/Essentials/items.json} (plus {@code custom_items.yml}), not Bukkit material names:
 * <pre>
 * #version: 2.22.0
 * {
 *   "diamond_pickaxe": { "material": "DIAMOND_PICKAXE" },
 *   "dpickaxe": "diamond_pickaxe",
 *   ...
 * }
 * </pre>
 * Pure: the import job reads the files and passes their text, tests pass fixtures.
 */
final class EssentialsItemDb {
    private static final EssentialsItemDb EMPTY = new EssentialsItemDb(Map.of());

    /** Lower-case alias or key to a material name ({@code DIAMOND_PICKAXE}). */
    private final Map<String, String> materials;

    private EssentialsItemDb(Map<String, String> materials) {
        this.materials = materials;
    }

    static EssentialsItemDb empty() {
        return EMPTY;
    }

    /**
     * @param itemsJson   contents of items.json ({@code #} comment lines allowed), or {@code null}
     * @param customItems custom_items.yml ({@code aliases: {alias: key}}), or {@code null}
     */
    static EssentialsItemDb parse(String itemsJson, ConfigurationNode customItems) {
        Map<String, String> keys = new HashMap<>();
        Map<String, String> aliases = new HashMap<>();
        if (itemsJson != null && !itemsJson.isBlank()) {
            StringBuilder json = new StringBuilder();
            for (String line : itemsJson.split("\n", -1)) {
                if (!line.stripLeading().startsWith("#")) {
                    json.append(line).append('\n');
                }
            }
            JsonObject root = JsonParser.parseString(json.toString()).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                String key = entry.getKey().toLowerCase(Locale.ROOT);
                JsonElement value = entry.getValue();
                if (value.isJsonObject()) {
                    JsonElement material = value.getAsJsonObject().get("material");
                    if (material != null && material.isJsonPrimitive()) {
                        keys.put(key, material.getAsString().toUpperCase(Locale.ROOT));
                    }
                } else if (value.isJsonPrimitive()) {
                    aliases.put(key, value.getAsString().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (customItems != null && !customItems.virtual()) {
            ConfigurationNode section = customItems.node("aliases");
            for (var entry : section.childrenMap().entrySet()) {
                String target = entry.getValue().getString();
                if (target != null) {
                    aliases.put(String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT), target.toLowerCase(Locale.ROOT));
                }
            }
        }
        Map<String, String> out = new HashMap<>(keys);
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            String target = alias.getValue();
            // An alias may point at another alias; follow a few hops.
            for (int hop = 0; hop < 4 && !keys.containsKey(target) && aliases.containsKey(target); hop++) {
                target = aliases.get(target);
            }
            String material = keys.get(target);
            if (material == null && target.indexOf(':') < 0) {
                material = target.toUpperCase(Locale.ROOT);
            }
            if (material != null) {
                out.put(alias.getKey(), material);
            }
        }
        return new EssentialsItemDb(Map.copyOf(out));
    }

    /**
     * The material name for an Essentials item name: an items.json key or alias, else the name itself in
     * lower case (a Bukkit material name such as {@code diamond_sword} or {@code minecraft:diamond_sword}).
     */
    String material(String name) {
        if (name == null) {
            return null;
        }
        String lower = name.trim().toLowerCase(Locale.ROOT);
        String found = materials.get(lower);
        if (found == null && lower.startsWith("minecraft:")) {
            found = materials.get(lower.substring("minecraft:".length()));
        }
        return found == null ? lower : found.toLowerCase(Locale.ROOT);
    }

    int size() {
        return materials.size();
    }
}
