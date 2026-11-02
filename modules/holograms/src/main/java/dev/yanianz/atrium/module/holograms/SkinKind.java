package dev.yanianz.atrium.module.holograms;

import java.util.Locale;

/** The front views of a player skin that an image line can render. */
enum SkinKind {
    HEAD("head"),
    BUST("bust"),
    BODY("body");

    private final String key;

    SkinKind(String key) {
        this.key = key;
    }

    String key() {
        return key;
    }

    static SkinKind parse(String value) {
        String lower = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        for (SkinKind kind : values()) {
            if (kind.key.equals(lower)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unknown image type '" + value + "' (use head, bust or body)");
    }

    /** Width in skin pixels of the front view. */
    int width() {
        return this == HEAD ? 8 : 16;
    }

    /** Height in skin pixels of the front view. */
    int height() {
        return switch (this) {
            case HEAD -> 8;
            case BUST -> 20;
            case BODY -> 32;
        };
    }
}
