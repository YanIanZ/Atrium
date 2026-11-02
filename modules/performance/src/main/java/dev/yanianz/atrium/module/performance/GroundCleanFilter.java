package dev.yanianz.atrium.module.performance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Compiled ground-cleanup whitelist.
 *
 * <p>Materials and custom names are matched case-insensitively and support {@code *} and {@code ?}
 * wildcards. The raw config values are compiled once; matching itself does not touch the registry or
 * the world, so it is safe on every region thread.
 */
final class GroundCleanFilter {
    private final List<Pattern> materials;
    private final List<Pattern> names;

    GroundCleanFilter(List<String> materialPatterns, List<String> namePatterns) {
        this.materials = compile(materialPatterns, true);
        this.names = compile(namePatterns, false);
    }

    static GroundCleanFilter from(PerformanceConfig.GroundClean config) {
        return new GroundCleanFilter(config.whitelistedMaterials, config.whitelistedNames);
    }

    /** True when the material or the custom display name is whitelisted. */
    boolean keeps(ItemStack stack, String plainName) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        Material type = stack.getType();
        return keepsMaterial(type.getKey().toString(), type.getKey().getKey(), type.name().toLowerCase(Locale.ROOT))
                || keepsName(plainName);
    }

    boolean keepsMaterial(String key, String path, String enumName) {
        for (Pattern pattern : materials) {
            if (pattern.matcher(key).matches() || pattern.matcher(path).matches()
                    || pattern.matcher(enumName).matches()) {
                return true;
            }
        }
        return false;
    }

    boolean keepsName(String plainName) {
        if (plainName == null || plainName.isBlank()) {
            return false;
        }
        String name = plainName.strip();
        for (Pattern pattern : names) {
            if (pattern.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    boolean keeps(Item item) {
        return keeps(item.getItemStack(), displayName(item.getItemStack()));
    }

    /** Plain text of a custom name, or null when the item has no custom name. */
    static String displayName(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return null;
        }
        var name = meta.displayName();
        return name == null ? null : PlainTextComponentSerializer.plainText().serialize(name);
    }

    private static List<Pattern> compile(List<String> raw, boolean material) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Pattern> result = new ArrayList<>(raw.size());
        for (String value : raw) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String text = value.strip();
            if (material) {
                text = normalizeMaterial(text);
            }
            result.add(glob(text));
        }
        return List.copyOf(result);
    }

    static String normalizeMaterial(String value) {
        String text = value.strip().toLowerCase(Locale.ROOT).replace(' ', '_');
        return text.startsWith("minecraft:") ? text.substring("minecraft:".length()) : text;
    }

    /** Anchored, case-insensitive glob. Everything except * and ? is quoted. */
    static Pattern glob(String text) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
