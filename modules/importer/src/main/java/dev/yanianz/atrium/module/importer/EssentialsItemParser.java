package dev.yanianz.atrium.module.importer;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses one EssentialsX kit item line, documented as {@code <item>[:data] [amount] [meta...]}:
 * <pre>
 * - diamond_sword 1 name:&amp;bExcalibur lore:First_line|Second_line sharpness:5 unbreaking:3
 * - bread 16
 * - '@H4sIAAAA...'          # NBT-serialized item (use-nbt-serialization-in-createkit)
 * - /give {player} cake    # command line
 * </pre>
 * In meta values {@code _} stands for a space and {@code |} separates lore lines. Every meta key that is
 * not a name, lore or a numeric enchantment level is listed as unsupported for that item.
 */
final class EssentialsItemParser {
    /** Meta keys Essentials understands that Atrium's kit import does not carry over. */
    private static final Set<String> KNOWN_UNSUPPORTED = Set.of("color", "player", "owner", "unbreakable", "itemflags",
            "power", "effect", "duration", "splash", "lingering", "book", "author", "title", "page", "banner",
            "shield", "basecolor", "fade", "potion", "custommodeldata", "armor-trim", "trim", "firework",
            "fireworkeffect", "skull", "texture");

    private EssentialsItemParser() {}

    enum Kind { ITEM, COMMAND, MONEY }

    record Line(Kind kind, KitItemSpec item, String text) {}

    static Line parse(String raw, int slot) {
        String line = raw == null ? "" : raw.strip();
        if (line.isEmpty()) {
            return new Line(Kind.ITEM, KitItemSpec.broken(slot, "empty item line"), line);
        }
        if (line.startsWith("/")) {
            return new Line(Kind.COMMAND, null, line);
        }
        if (line.startsWith("$")) {
            return new Line(Kind.MONEY, null, line);
        }
        if (line.startsWith("@")) {
            try {
                byte[] bytes = Base64.getDecoder().decode(line.substring(1).strip());
                return new Line(Kind.ITEM, KitItemSpec.serialized(slot, bytes), line);
            } catch (IllegalArgumentException e) {
                return new Line(Kind.ITEM, KitItemSpec.broken(slot, "malformed serialized item"), line);
            }
        }
        String[] tokens = line.split("\\s+");
        String material = tokens[0];
        List<String> unsupported = new ArrayList<>();
        int colon = material.indexOf(':');
        if (colon > 0 && !material.toLowerCase(Locale.ROOT).startsWith("minecraft:")) {
            unsupported.add("legacy data value '" + material.substring(colon + 1) + "'");
            material = material.substring(0, colon);
        }
        if (material.chars().allMatch(Character::isDigit)) {
            return new Line(Kind.ITEM, KitItemSpec.broken(slot, "numeric item id '" + material + "' is not supported"), line);
        }
        int index = 1;
        int amount = 1;
        if (tokens.length > 1 && tokens[1].chars().allMatch(Character::isDigit)) {
            try {
                amount = Math.max(1, Math.min(99, Integer.parseInt(tokens[1])));
            } catch (NumberFormatException e) {
                amount = 1;
            }
            index = 2;
        }
        String name = null;
        List<String> lore = new ArrayList<>();
        Map<String, Integer> enchantments = new LinkedHashMap<>();
        for (int i = index; i < tokens.length; i++) {
            String token = tokens[i];
            int split = token.indexOf(':');
            if (split <= 0) {
                unsupported.add("'" + token + "'");
                continue;
            }
            String key = token.substring(0, split).toLowerCase(Locale.ROOT);
            String value = token.substring(split + 1);
            switch (key) {
                case "name" -> name = spaces(value);
                case "lore" -> {
                    for (String part : value.split("\\|")) {
                        lore.add(spaces(part));
                    }
                }
                default -> {
                    Integer level = level(value);
                    if (level != null && !KNOWN_UNSUPPORTED.contains(key)) {
                        enchantments.put(key, level);
                    } else {
                        unsupported.add(key);
                    }
                }
            }
        }
        return new Line(Kind.ITEM, new KitItemSpec(slot, material.toLowerCase(Locale.ROOT), amount, name, lore,
                enchantments, null, unsupported, null), line);
    }

    private static String spaces(String value) {
        return value.replace("\\_", "\u0000").replace('_', ' ').replace('\u0000', '_');
    }

    private static Integer level(String value) {
        try {
            int level = Integer.parseInt(value.trim());
            return level > 0 && level <= 255 ? level : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
