package dev.yanianz.atrium.module.holograms;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What one hologram line shows. Parsed from the stored raw string:
 * <ul>
 *   <li>{@code #item:<material>}: an item ({@code #item:diamond_sword}).</li>
 *   <li>{@code #itemstack:<base64>}: a saved item stack (written by {@code /holo addline <name> #hand}).</li>
 *   <li>{@code #block:<block data>}: a block ({@code #block:oak_stairs[facing=east]}).</li>
 *   <li>{@code #head:<player>}, {@code #img:head:<player>}: the head of a player; without a name the head of
 *       the viewer ({@code #img:head}).</li>
 *   <li>{@code #iteminhand} or {@code %itemInHand%}: the item the viewer holds (native per-viewer copy).</li>
 *   <li>{@code #cmd:<n>} or {@code %CustomModelData:<n>%}: an item resolved from the configured
 *       custom-model-data table.</li>
 *   <li>anything else: a MiniMessage text template. A leading {@code \#} escapes a literal {@code #}.</li>
 * </ul>
 * Pure: nothing here touches the server, so values are validated separately before they are stored.
 */
sealed interface LineContent {
    String ITEM = "#item:";
    String ITEM_STACK = "#itemstack:";
    String BLOCK = "#block:";
    String HEAD = "#head:";
    String IMAGE = "#img:";
    String IMAGE_HEAD = "#img:head";
    String IN_HAND = "#iteminhand";
    String CMD = "#cmd:";

    /** Names that can be resolved to a head without any lookup; nothing that could break out of a profile. */
    Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_.]{1,16}");
    Pattern CUSTOM_MODEL_DATA = Pattern.compile("(?i)^%CustomModelData:(\\d{1,9})%$");

    enum IconKind {
        /** The main-hand item of the viewer; needs a per-viewer copy. */
        HAND,
        /** An item from the configured custom-model-data table. */
        CUSTOM_MODEL_DATA
    }

    record Text(String template) implements LineContent {}

    record Item(String material) implements LineContent {}

    record SavedItem(String base64) implements LineContent {}

    record Block(String blockData) implements LineContent {}

    /** A player head; an empty name means the viewer. */
    record Head(String player) implements LineContent {}

    /** The front of a player skin rendered as coloured text pixels. */
    record Image(String player, SkinKind kind, int size) implements LineContent {}

    record Icon(IconKind kind, int customModelData) implements LineContent {}

    /** Throws {@link IllegalArgumentException} when a prefix is present but its value is invalid. */
    static LineContent parse(String raw) {
        if (raw.startsWith("\\#")) {
            return new Text(raw.substring(1));
        }
        String lower = raw.toLowerCase(Locale.ROOT).strip();
        if (lower.startsWith(ITEM_STACK)) {
            return new SavedItem(value(raw, ITEM_STACK));
        }
        if (lower.startsWith(ITEM)) {
            return new Item(value(raw, ITEM).toLowerCase(Locale.ROOT));
        }
        if (lower.startsWith(BLOCK)) {
            return new Block(value(raw, BLOCK));
        }
        if (lower.startsWith(HEAD)) {
            return new Head(player(value(raw, HEAD)));
        }
        if (lower.startsWith(IMAGE)) {
            return image(raw);
        }
        if (lower.equals(IN_HAND) || lower.equals("%iteminhand%")) {
            return new Icon(IconKind.HAND, 0);
        }
        if (lower.startsWith(CMD)) {
            return new Icon(IconKind.CUSTOM_MODEL_DATA, number(value(raw, CMD)));
        }
        Matcher model = CUSTOM_MODEL_DATA.matcher(raw.strip());
        if (model.matches()) {
            return new Icon(IconKind.CUSTOM_MODEL_DATA, number(model.group(1)));
        }
        return new Text(raw);
    }

    /**
     * {@code #img:head} keeps its old meaning (a head item of the viewer); {@code #img:<kind>:<player>[:<size>]}
     * is an image line. The size (1-4) repeats every skin pixel.
     */
    private static LineContent image(String raw) {
        String rest = raw.substring(IMAGE.length()).strip();
        String[] parts = rest.split(":", -1);
        String kindName = parts.length == 0 ? "" : parts[0];
        if (parts.length == 1) {
            if (kindName.equalsIgnoreCase("head")) {
                return new Head("");
            }
            throw new IllegalArgumentException(IMAGE + kindName + " needs a player name");
        }
        SkinKind kind = SkinKind.parse(kindName);
        String name = player(parts[1]);
        if (name.isEmpty()) {
            throw new IllegalArgumentException(IMAGE + kind.key() + " needs a player name");
        }
        int size = 1;
        if (parts.length >= 3) {
            size = size(parts[2]);
        }
        if (parts.length > 3) {
            throw new IllegalArgumentException(IMAGE + kind.key() + " takes :player[:size]");
        }
        return new Image(name, kind, size);
    }

    private static int size(String value) {
        int parsed;
        try {
            parsed = Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("image size must be a whole number from 1 to 4");
        }
        if (parsed < 1 || parsed > 4) {
            throw new IllegalArgumentException("image size must be a whole number from 1 to 4");
        }
        return parsed;
    }

    private static String value(String raw, String prefix) {
        String value = raw.substring(prefix.length()).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(prefix + " needs a value");
        }
        return value;
    }

    private static String player(String name) {
        String value = name == null ? "" : name.strip();
        if (value.isEmpty()) {
            return "";
        }
        if (!PLAYER_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("a player name may only contain a-z, A-Z, 0-9, _ and . (1-16)");
        }
        return value;
    }

    private static int number(String value) {
        int parsed;
        try {
            parsed = Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("custom model data must be a whole number");
        }
        if (parsed < 0) {
            throw new IllegalArgumentException("custom model data must not be negative");
        }
        return parsed;
    }

    default boolean isText() {
        return this instanceof Text;
    }

    /** True when the appearance depends on the viewer, i.e. the hologram needs a copy per viewer. */
    default boolean viewerDependent() {
        return this instanceof Icon icon && icon.kind() == IconKind.HAND
                || this instanceof Head head && head.player().isEmpty();
    }

    /** Item-like lines are rendered by an ItemDisplay. */
    default boolean isItemLike() {
        return this instanceof Item || this instanceof SavedItem || this instanceof Head || this instanceof Icon;
    }
}
