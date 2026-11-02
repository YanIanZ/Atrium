package dev.yanianz.atrium.module.holograms;

import java.util.Locale;

/**
 * What one hologram line shows. Parsed from the stored raw string:
 * <ul>
 *   <li>{@code #item:<material>}: an item ({@code #item:diamond_sword}).</li>
 *   <li>{@code #itemstack:<base64>}: a saved item stack (written by {@code /holo addline <name> #hand}).</li>
 *   <li>{@code #block:<block data>}: a block ({@code #block:oak_stairs[facing=east]}).</li>
 *   <li>anything else: a MiniMessage text template. A leading {@code \#} escapes a literal {@code #}.</li>
 * </ul>
 * Pure: nothing here touches the server, so values are validated separately before they are stored.
 */
sealed interface LineContent {
    String ITEM = "#item:";
    String ITEM_STACK = "#itemstack:";
    String BLOCK = "#block:";

    record Text(String template) implements LineContent {}

    record Item(String material) implements LineContent {}

    record SavedItem(String base64) implements LineContent {}

    record Block(String blockData) implements LineContent {}

    /** Throws {@link IllegalArgumentException} when a prefix is present but its value is empty. */
    static LineContent parse(String raw) {
        if (raw.startsWith("\\#")) {
            return new Text(raw.substring(1));
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.startsWith(ITEM_STACK)) {
            return new SavedItem(value(raw, ITEM_STACK));
        }
        if (lower.startsWith(ITEM)) {
            return new Item(value(raw, ITEM).toLowerCase(Locale.ROOT));
        }
        if (lower.startsWith(BLOCK)) {
            return new Block(value(raw, BLOCK));
        }
        return new Text(raw);
    }

    private static String value(String raw, String prefix) {
        String value = raw.substring(prefix.length()).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(prefix + " needs a value");
        }
        return value;
    }

    default boolean isText() {
        return this instanceof Text;
    }
}
