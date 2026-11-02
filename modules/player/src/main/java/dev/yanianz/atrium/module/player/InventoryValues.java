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

    static List<Component> lore(List<? extends Component> original, String operation, int line, Component text) {
        var result = new ArrayList<Component>(original == null ? List.of() : original);
        switch (operation) {
            case "add" -> result.add(Objects.requireNonNull(text));
            case "clear" -> result.clear();
            case "set", "remove" -> {
                if (line < 1 || line > result.size()) throw new IllegalArgumentException("No such lore line");
                if (operation.equals("set")) result.set(line - 1, Objects.requireNonNull(text));
                else result.remove(line - 1);
            }
            default -> throw new IllegalArgumentException("Unknown lore operation");
        }
        return List.copyOf(result);
    }
}
