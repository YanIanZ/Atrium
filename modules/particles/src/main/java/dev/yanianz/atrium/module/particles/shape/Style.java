package dev.yanianz.atrium.module.particles.shape;

import java.util.List;
import java.util.Locale;

/**
 * One selectable particle shape. Pure data: the actual offsets are computed by {@link Shapes} so the
 * geometry can be unit-tested without a server.
 */
public enum Style {
    HALO("halo", Category.BODY, false, 0.65, 1.0, 4.0),
    HELIX("helix", Category.BODY, false, 0.55, 1.0, 6.0),
    ORBIT("orbit", Category.BODY, false, 0.8, 1.0, 6.0),
    WINGS("wings", Category.BODY, false, 1.1, 1.0, 0.0),
    CLOUD("cloud", Category.AMBIENT, false, 0.8, 1.0, 8.0),
    CAPE("cape", Category.BODY, false, 0.9, 1.0, 3.0),
    CROWN("crown", Category.BODY, false, 0.55, 1.0, 4.0),
    SPHERE("sphere", Category.BODY, false, 0.9, 1.0, 5.0),
    BEAM("beam", Category.BODY, false, 0.5, 1.0, 8.0),
    PULSE_RING("pulse_ring", Category.MOVEMENT, false, 0.9, 1.0, 4.0),
    FOOTSTEPS("footsteps", Category.MOVEMENT, true, 0.35, 1.0, 0.0),
    TRAIL("trail", Category.MOVEMENT, true, 0.7, 1.0, 6.0),
    QUADHELIX("quadhelix", Category.BODY, false, 0.6, 1.0, 8.0),
    ARROWS_TRAIL("arrows_trail", Category.MOVEMENT, true, 0.8, 1.0, 6.0);

    /** Menu grouping. */
    public enum Category {
        BODY("body"),
        MOVEMENT("movement"),
        AMBIENT("ambient");

        private final String key;

        Category(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public static Category byName(String name) {
            if (name == null) {
                return null;
            }
            String normalized = name.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            for (Category category : values()) {
                if (category.key.equals(normalized)) {
                    return category;
                }
            }
            return null;
        }
    }

    private final String key;
    private final Category category;
    private final boolean movingOnly;
    private final double defaultRadius;
    private final double defaultDensity;
    private final double defaultSpeed;

    Style(String key, Category category, boolean movingOnly, double defaultRadius, double defaultDensity,
            double defaultSpeed) {
        this.key = key;
        this.category = category;
        this.movingOnly = movingOnly;
        this.defaultRadius = defaultRadius;
        this.defaultDensity = defaultDensity;
        this.defaultSpeed = defaultSpeed;
    }

    /** Lower-case command and permission key, e.g. {@code pulse_ring}. */
    public String key() {
        return key;
    }

    public Category category() {
        return category;
    }

    /** Footsteps, trails and arrow trails only draw while the player moved. */
    public boolean movingOnly() {
        return movingOnly;
    }

    public double defaultRadius() {
        return defaultRadius;
    }

    public double defaultDensity() {
        return defaultDensity;
    }

    public double defaultSpeed() {
        return defaultSpeed;
    }

    public String permission() {
        return "atrium.particles.style." + key;
    }

    public static Style byName(String name) {
        if (name == null) {
            return null;
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (Style style : values()) {
            if (style.key.equals(normalized)) {
                return style;
            }
        }
        return switch (normalized) {
            case "spiral" -> HELIX;
            case "rain" -> CLOUD;
            case "ring" -> PULSE_RING;
            case "arrows", "arrow" -> ARROWS_TRAIL;
            default -> null;
        };
    }

    public static List<Style> inCategory(Category category) {
        return List.of(values()).stream().filter(style -> style.category == category).toList();
    }
}
