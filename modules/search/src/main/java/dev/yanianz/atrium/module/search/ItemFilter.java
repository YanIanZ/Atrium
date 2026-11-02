package dev.yanianz.atrium.module.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Material;

/**
 * Matching rules of {@code /search item}. Pure logic, no server or world access, so it is unit tested.
 *
 * <p>Comparing only the material is not enough. Here the material is the base filter and every
 * option the player typed ({@code name:}, {@code cmd:}) is an additional AND condition: an item that
 * matches the material but not the name or CustomModelData must never be reported.
 */
public final class ItemFilter {
    /** Matches an option key at the start of the input or after whitespace. */
    private static final Pattern OPTION = Pattern.compile("(?i)(?:^|\\s)(name|cmd|player):");
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,32}");

    private final Material material;
    private final String name;
    private final Integer customModelData;

    public ItemFilter(Material material, String name, Integer customModelData) {
        this.material = Objects.requireNonNull(material, "material");
        String normalised = normalise(name);
        this.name = normalised == null || normalised.isEmpty() ? null : normalised;
        this.customModelData = customModelData;
    }

    public Material material() {
        return material;
    }

    /** The normalised name fragment, or {@code null} when the option was not used. */
    public String name() {
        return name;
    }

    public Integer customModelData() {
        return customModelData;
    }

    public boolean matches(ItemKey item) {
        if (item == null || item.type() != material) {
            return false;
        }
        if (name != null) {
            String itemName = normalise(item.plainName());
            if (itemName == null || !itemName.contains(name)) {
                return false;
            }
        }
        if (customModelData != null && !customModelData.equals(item.customModelData())) {
            return false;
        }
        return true;
    }

    /** Short human-readable description used in results headers. */
    public String describe() {
        StringBuilder out = new StringBuilder(material.getKey().getKey());
        if (name != null) {
            out.append(" name~\"").append(name).append('"');
        }
        if (customModelData != null) {
            out.append(" cmd=").append(customModelData);
        }
        return out.toString();
    }

    /** Lower-cased, whitespace-stripped text, so "Red Sword" and "redsword" are the same fragment. */
    static String normalise(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    /** Values of the optional {@code name:}, {@code cmd:} and {@code player:} tokens, in any order. */
    public record Options(String name, Integer customModelData, String player) {
        public static final Options NONE = new Options(null, null, null);
    }

    /**
     * Parses the option tail of {@code /search item}. Values may be quoted ({@code name:"Red Sword"})
     * and a name may contain spaces. Throws {@link IllegalArgumentException} with an admin-readable
     * reason for unknown keys, empty values and malformed numbers.
     */
    public static Options parseOptions(String raw) {
        if (raw == null || raw.isBlank()) {
            return Options.NONE;
        }
        String input = raw.trim();
        Matcher matcher = OPTION.matcher(input);
        List<Mark> marks = new ArrayList<>();
        while (matcher.find()) {
            marks.add(new Mark(matcher.group(1).toLowerCase(Locale.ROOT), matcher.start(), matcher.end()));
        }
        if (marks.isEmpty()) {
            throw new IllegalArgumentException("start with name: or cmd:");
        }
        if (marks.getFirst().start() > 0) {
            throw new IllegalArgumentException("unknown text before the first option");
        }
        String name = null;
        Integer cmd = null;
        String player = null;
        for (int i = 0; i < marks.size(); i++) {
            Mark mark = marks.get(i);
            int end = i + 1 < marks.size() ? marks.get(i + 1).start() : input.length();
            String value = unquote(input.substring(mark.end(), end).trim());
            if (value.isEmpty()) {
                throw new IllegalArgumentException("missing value for " + mark.key() + ":");
            }
            switch (mark.key()) {
                case "name" -> name = value;
                case "cmd" -> cmd = parseCustomModelData(value);
                case "player" -> {
                    if (!PLAYER_NAME.matcher(value).matches()) {
                        throw new IllegalArgumentException("player name must be 1-32 letters, digits, _ . or -");
                    }
                    player = value;
                }
                default -> throw new IllegalArgumentException("unknown option " + mark.key() + ":");
            }
        }
        return new Options(name, cmd, player);
    }

    private static int parseCustomModelData(String value) {
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("cmd: needs a whole number");
        }
        if (parsed < 0) {
            throw new IllegalArgumentException("cmd: cannot be negative");
        }
        return parsed;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' || first == '\'') && first == last) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    /** The attributes of one item stack that the filter compares. */
    public record ItemKey(Material type, String plainName, Integer customModelData) {}

    private record Mark(String key, int start, int end) {}
}
