package dev.yanianz.atrium.module.chatbubbles;

import java.util.Locale;
import java.util.function.BiConsumer;

/** Configured bubble mode: {@code auto|packets|follow}. */
enum BubbleMode {
    /** Client-side packets when PacketEvents is usable, otherwise the server-side fallback. */
    AUTO,
    /** A client-side TextDisplay mounted on the speaker; zero visible lag. */
    PACKETS,
    /** A short-lived server TextDisplay that is moved every tick with velocity prediction. */
    FOLLOW;

    String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    static BubbleMode parse(String raw, BiConsumer<String, String> problems) {
        if (raw == null || raw.isBlank()) {
            return AUTO;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            if (problems != null) {
                problems.accept("mode", "'" + raw + "' is not auto, packets or follow; using auto");
            }
            return AUTO;
        }
    }

    /**
     * The renderer that will actually run. An explicit {@code packets} without a usable PacketEvents
     * falls back to {@code follow} so bubbles keep working; the caller logs why once.
     */
    static BubbleMode effective(BubbleMode configured, boolean packetsAvailable) {
        if (configured == FOLLOW || !packetsAvailable) {
            return FOLLOW;
        }
        return PACKETS;
    }
}
