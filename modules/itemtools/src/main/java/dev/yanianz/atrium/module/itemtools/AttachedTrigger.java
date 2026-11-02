package dev.yanianz.atrium.module.itemtools;

import java.util.Locale;
import java.util.Optional;

/** The click/use trigger an attached command listens to. */
enum AttachedTrigger {
    RIGHT("right"),
    LEFT("left"),
    CONSUME("consume");

    private final String id;

    AttachedTrigger(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    static Optional<AttachedTrigger> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalized = id.strip().toLowerCase(Locale.ROOT);
        for (AttachedTrigger trigger : values()) {
            if (trigger.id.equals(normalized)) {
                return Optional.of(trigger);
            }
        }
        return Optional.empty();
    }
}
