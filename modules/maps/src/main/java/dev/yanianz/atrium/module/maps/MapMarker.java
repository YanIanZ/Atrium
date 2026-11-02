package dev.yanianz.atrium.module.maps;

import java.util.Objects;

/**
 * One marker in the map-neutral representation. Coordinates are block coordinates of the marker's
 * world; {@code areaRadius > 0} means the marker is drawn as a square area instead of a point.
 *
 * <p>{@code label} is the raw text (a warp name, a configured admin label, ...). Map hooks apply
 * {@link MapText#escapeHtml(String)} before handing it to a plugin.
 */
public record MapMarker(String id, Category category, String world, double x, double y, double z,
        double areaRadius, String label) {
    public enum Category { WARP, SPAWN, PORTAL }

    public MapMarker {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(label, "label");
        if (id.isBlank()) {
            throw new IllegalArgumentException("blank marker id");
        }
        if (world.isBlank()) {
            throw new IllegalArgumentException("blank world");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("non-finite marker position");
        }
        if (!Double.isFinite(areaRadius) || areaRadius < 0) {
            throw new IllegalArgumentException("invalid area radius");
        }
    }

    public boolean isArea() {
        return areaRadius > 0;
    }
}
