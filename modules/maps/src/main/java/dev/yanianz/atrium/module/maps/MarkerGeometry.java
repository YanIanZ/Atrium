package dev.yanianz.atrium.module.maps;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Grouping and geometry helpers shared by the web-map backends.
 *
 * <p>Everything here is a pure function: the marker placement rules can be tested without a server
 * or a map plugin, and every backend derives its shapes from the same numbers.
 */
public final class MarkerGeometry {
    /** Blocks drawn below a portal marker's origin block. */
    private static final float PORTAL_BELOW = 1;
    /** Blocks drawn above a portal marker's origin block. */
    private static final float PORTAL_ABOVE = 3;

    private MarkerGeometry() {}

    /** Horizontal square around a marker, in world block coordinates. */
    public record Rect(double minX, double minZ, double maxX, double maxZ) {}

    /** Vertical extent of an extruded area marker. */
    public record YRange(float min, float max) {}

    /** Square of {@code radius} blocks around {@code x,z}; a non-positive radius collapses to a point. */
    public static Rect square(double x, double z, double radius) {
        double r = Double.isFinite(radius) && radius > 0 ? radius : 0;
        return new Rect(x - r, z - r, x + r, z + r);
    }

    /** Vertical extent drawn for a portal whose origin block is at {@code y}. */
    public static YRange portalRange(double y) {
        return new YRange((float) y - PORTAL_BELOW, (float) y + PORTAL_ABOVE);
    }

    /**
     * Groups markers by world name. Markers inside a world are sorted by id and worlds are sorted by
     * name, so two runs over the same set always produce the same backend diff order.
     */
    public static Map<String, List<MapMarker>> byWorld(Collection<MapMarker> markers) {
        Map<String, List<MapMarker>> grouped = new LinkedHashMap<>();
        if (markers == null || markers.isEmpty()) {
            return grouped;
        }
        List<MapMarker> sorted = new ArrayList<>(markers);
        sorted.sort(Comparator.comparing(MapMarker::world).thenComparing(MapMarker::id));
        for (MapMarker marker : sorted) {
            grouped.computeIfAbsent(marker.world(), key -> new ArrayList<>()).add(marker);
        }
        return grouped;
    }
}
