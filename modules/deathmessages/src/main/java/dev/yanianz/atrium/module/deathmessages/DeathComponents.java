package dev.yanianz.atrium.module.deathmessages;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.Style;

/** Component helpers for values inserted into trusted death templates. */
final class DeathComponents {
    private DeathComponents() {}

    /**
     * Copies a component without hover, click or insertion events. Item display names are player-authored
     * and must never smuggle an interactive event into a broadcast death message.
     */
    static Component withoutEvents(Component input) {
        Component result = input.children(input.children().stream().map(DeathComponents::withoutEvents).toList());
        Style style = result.style();
        if (style.hoverEvent() != null || style.clickEvent() != null || style.insertion() != null) {
            result = result.style(style.hoverEvent(null).clickEvent(null).insertion(null));
        }
        return result;
    }
}
