package dev.yanianz.atrium.module.worldrules;

import java.util.Locale;

/** Direction arrow for the compass boss bar, relative to where the holder is looking. */
final class CompassBearing {
    /** Arrow per 45 degree step, clockwise from straight ahead. */
    private static final String[] ARROWS = {"\u2B06", "\u2197", "\u27A1", "\u2198", "\u2B07", "\u2199", "\u2B05", "\u2196"};

    private CompassBearing() {}

    /** Minecraft yaw that points from the holder to a target at {@code dx}/{@code dz}. */
    static double targetYaw(double dx, double dz) {
        if (dx == 0 && dz == 0) {
            return 0;
        }
        return Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** Clockwise angle from the holder's view to the target, in {@code (-180, 180]}. */
    static double relative(double targetYaw, float viewYaw) {
        double value = (targetYaw - viewYaw) % 360.0;
        if (value <= -180.0) {
            value += 360.0;
        } else if (value > 180.0) {
            value -= 360.0;
        }
        return value;
    }

    static String arrow(double dx, double dz, float viewYaw) {
        if (dx == 0 && dz == 0) {
            return ARROWS[4];
        }
        int index = (int) Math.round(relative(targetYaw(dx, dz), viewYaw) / 45.0);
        return ARROWS[Math.floorMod(index, ARROWS.length)];
    }

    /** Rounded horizontal distance for the boss bar title. */
    static long distance(double dx, double dz) {
        return Math.round(Math.sqrt(dx * dx + dz * dz));
    }

    static String formatDistance(long blocks) {
        return String.format(Locale.ROOT, "%dm", Math.max(0, blocks));
    }
}
