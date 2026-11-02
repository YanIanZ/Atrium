package dev.yanianz.atrium.module.npcs;

import dev.yanianz.atrium.api.npcs.NpcInteractEvent;
import java.util.Locale;
import java.util.Optional;

/** Left/right click names used by commands and storage. */
enum NpcInteractClick {
    LEFT,
    RIGHT;

    NpcInteractEvent.Click event() {
        return this == LEFT ? NpcInteractEvent.Click.LEFT : NpcInteractEvent.Click.RIGHT;
    }

    String configName() {
        return name().toLowerCase(Locale.ROOT);
    }

    static Optional<NpcInteractClick> parse(String input) {
        if (input == null) {
            return Optional.empty();
        }
        return switch (input.trim().toLowerCase(Locale.ROOT)) {
            case "left", "l" -> Optional.of(LEFT);
            case "right", "r" -> Optional.of(RIGHT);
            default -> Optional.empty();
        };
    }
}
