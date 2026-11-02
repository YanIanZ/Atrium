package dev.yanianz.atrium.core.menu;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.Style;

/** Splits a component at newlines into separate lore lines, keeping styles. */
final class Lines {
    private static final String MARKER = "\u0000";

    private Lines() {}

    static List<Component> split(Component component) {
        if (!containsNewline(component)) {
            return List.of(component);
        }
        // Replace newline text with a marker child and cut the flattened children at the markers.
        Component marked = component.replaceText(TextReplacementConfig.builder()
                .matchLiteral("\n").replacement(Component.text(MARKER)).build());
        List<Component> lines = new ArrayList<>();
        List<Component> current = new ArrayList<>();
        collect(marked, marked.style(), current, lines);
        lines.add(Component.empty().children(current));
        return lines;
    }

    private static void collect(Component node, Style inherited,
            List<Component> current, List<Component> lines) {
        // The node's own style wins; anything it leaves unset is inherited from its parents.
        Style style = node.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        if (node instanceof TextComponent text) {
            if (text.content().equals(MARKER)) {
                lines.add(Component.empty().children(new ArrayList<>(current)));
                current.clear();
            } else if (!text.content().isEmpty()) {
                current.add(Component.text(text.content(), style));
            }
        } else {
            current.add(node.children(List.of()).style(style));
        }
        for (Component child : node.children()) {
            collect(child, style, current, lines);
        }
    }

    private static boolean containsNewline(Component component) {
        if (component instanceof TextComponent text && text.content().contains("\n")) {
            return true;
        }
        for (Component child : component.children()) {
            if (containsNewline(child)) {
                return true;
            }
        }
        return false;
    }
}
