package dev.yanianz.atrium.module.portals;

/**
 * Kickback when a player walks into a portal they may not use: the movement vector is reversed and
 * damped, so the player is pushed back out instead of standing inside a locked portal.
 */
final class PortalKickback {
    /** Strongest kick that is still safe near a wall. */
    private static final double MAX = 3.0;

    record Vec(double x, double y, double z) {}

    private PortalKickback() {}

    /**
     * @param dx       movement on X in blocks
     * @param dy       movement on Y in blocks
     * @param dz       movement on Z in blocks
     * @param strength multiplier of the reversed movement (0.1 - 4)
     */
    static Vec velocity(double dx, double dy, double dz, double strength) {
        double scale = Math.clamp(strength, 0.1, 4.0);
        double x = clamp(-dx * scale);
        double y = clamp(-dy * scale);
        double z = clamp(-dz * scale);
        // Always a small push upwards, so a kickback cannot shove the player into the floor.
        if (y < 0.2) {
            y = 0.2;
        }
        return new Vec(x, y, z);
    }

    private static double clamp(double value) {
        return Math.clamp(value, -MAX, MAX);
    }
}
