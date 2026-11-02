package dev.yanianz.atrium.module.effects;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Built-in particle shapes. */
public enum Shape {
    BURST,
    RING,
    SPHERE,
    COLUMN,
    SPIRAL;

    /** Lower-case id used in commands and config files. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses a shape name case-insensitively; {@code null} when unknown. */
    public static @Nullable Shape parse(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip().toUpperCase(Locale.ROOT);
        for (Shape shape : values()) {
            if (shape.name().equals(value)) {
                return shape;
            }
        }
        return null;
    }

    public static List<String> ids() {
        return List.of(idsArray());
    }

    private static String[] idsArray() {
        Shape[] values = values();
        String[] ids = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            ids[i] = values[i].id();
        }
        return ids;
    }
}
