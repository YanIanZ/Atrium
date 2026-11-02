package dev.yanianz.atrium.module.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;

final class InventoryValues {
    private static final MiniMessage COLORS = MiniMessage.builder()
            .tags(TagResolver.resolver(StandardTags.color(), StandardTags.decorations())).build();

    private InventoryValues() { }

    static int amount(String input) {
        if (input.equalsIgnoreCase("all")) return Integer.MAX_VALUE;
        if (!input.matches("[0-9]+")) throw new IllegalArgumentException("Invalid item amount");
        int value = Integer.parseInt(input);
        if (value < 1) throw new IllegalArgumentException("Amount must be positive");
        return value;
    }

    // Unregistered tags stay literal. No general resolver, placeholders, click, hover or insertion.
    static Component text(String input, boolean color) {
        return color ? COLORS.deserialize(input) : Component.text(input);
    }

    static List<Component> lore(List<? extends Component> original, String operation, String line, Component text) {
        var result = new ArrayList<Component>(original == null ? List.of() : original);
        boolean all = line != null && line.strip().equals("*");
        switch (operation) {
            case "add" -> result.add(Objects.requireNonNull(text));
            case "insert" -> result.add(lineNumber(line, result.size(), true) - 1, Objects.requireNonNull(text));
            case "set" -> {
                if (all) {
                    if (result.isEmpty()) result.add(Objects.requireNonNull(text));
                    else result.replaceAll(ignored -> Objects.requireNonNull(text));
                } else {
                    result.set(lineNumber(line, result.size(), false) - 1, Objects.requireNonNull(text));
                }
            }
            case "remove" -> {
                if (all) result.clear();
                else result.remove(lineNumber(line, result.size(), false) - 1);
            }
            case "clear" -> result.clear();
            default -> throw new IllegalArgumentException("Unknown lore operation");
        }
        return List.copyOf(result);
    }

    /** Parses a one-based lore line; {@code *} is handled by the caller. */
    static int lineNumber(String input, int size, boolean insert) {
        String value = input == null ? "" : input.strip();
        if (!value.matches("[0-9]+")) throw new IllegalArgumentException("Lore line must be a number");
        int line = Integer.parseInt(value);
        int max = insert ? size + 1 : size;
        if (line < 1 || line > max) throw new IllegalArgumentException("No such lore line");
        return line;
    }
}
