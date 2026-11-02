package dev.yanianz.atrium.module.effects;

import java.util.Collection;

/**
 * Cheap proximity test for fixed effects. The renderer only reads positions it already collected on the
 * owning region thread; this class holds no state and is unit tested on its own.
 */
public final class Nearby {
    /** A viewer position in world coordinates. */
    public record Point(double x, double y, double z) {}

    private Nearby() {}

    /**
     * True when any point is within {@code radius} blocks (horizontal distance, squared check). Vertical
     * distance is ignored so a player standing in a cave under a beacon effect still counts as near; the
     * particle shape itself is vertical and small.
     */
    public static boolean anyWithin(Point centre, double radius, Collection<Point> players) {
        if (radius <= 0) {
            return false;
        }
        double limitSquared = radius * radius;
        for (Point player : players) {
            double dx = player.x() - centre.x();
            double dz = player.z() - centre.z();
            if (dx * dx + dz * dz <= limitSquared) {
                return true;
            }
        }
        return false;
    }

    public static Point of(double x, double y, double z) {
        return new Point(x, y, z);
    }
}
