package dev.yanianz.atrium.module.itemtools;

import dev.yanianz.atrium.api.itemtools.ItemFrameEditEvent;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.entity.ItemFrame;

/** The four {@code /itemframe} actions and how they map to the Bukkit item frame API. */
enum FrameAction {
    INVISIBLE("invisible", ItemFrameEditEvent.Action.INVISIBLE) {
        @Override
        void apply(ItemFrame frame) {
            frame.setVisible(false);
        }
    },
    VISIBLE("visible", ItemFrameEditEvent.Action.VISIBLE) {
        @Override
        void apply(ItemFrame frame) {
            frame.setVisible(true);
        }
    },
    FIXED("fixed", ItemFrameEditEvent.Action.FIXED) {
        @Override
        void apply(ItemFrame frame) {
            frame.setFixed(true);
        }
    },
    UNFIXED("unfixed", ItemFrameEditEvent.Action.UNFIXED) {
        @Override
        void apply(ItemFrame frame) {
            frame.setFixed(false);
        }
    };

    private final String id;
    private final ItemFrameEditEvent.Action eventAction;

    FrameAction(String id, ItemFrameEditEvent.Action eventAction) {
        this.id = id;
        this.eventAction = eventAction;
    }

    String id() {
        return id;
    }

    ItemFrameEditEvent.Action eventAction() {
        return eventAction;
    }

    abstract void apply(ItemFrame frame);

    static Optional<FrameAction> parse(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String wanted = input.trim().toLowerCase(Locale.ROOT);
        for (FrameAction action : values()) {
            if (action.id.equals(wanted)) {
                return Optional.of(action);
            }
        }
        return Optional.empty();
    }

    static List<String> names() {
        return List.of("invisible", "visible", "fixed", "unfixed");
    }
}
