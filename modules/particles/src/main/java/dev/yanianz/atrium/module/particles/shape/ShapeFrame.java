package dev.yanianz.atrium.module.particles.shape;

/**
 * Everything a shape generator needs for one render tick. All values are pure data, so the generators can
 * run in unit tests.
 *
 * @param tick    monotonically increasing render tick
 * @param yaw     player yaw in degrees (0 = south, 90 = west)
 * @param moving  the player moved horizontally since the previous render
 * @param radius  shape radius in blocks
 * @param density point density multiplier (below 1 thins the shape)
 * @param speed   animation speed in degrees per render tick
 * @param wings   bitmap used by {@link Style#WINGS}
 */
public record ShapeFrame(int tick, float yaw, boolean moving, double radius, double density, double speed,
        WingPattern wings) {
    public ShapeFrame {
        radius = clamp(radius, 0.05, 8.0);
        density = clamp(density, 0.05, 8.0);
        speed = clamp(speed, 0.0, 360.0);
        if (wings == null) {
            wings = WingPattern.defaults();
        }
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    /** Animation phase in radians. */
    public double phase() {
        return Math.toRadians(tick * speed);
    }

    public double yawRadians() {
        return Math.toRadians(yaw);
    }
}
