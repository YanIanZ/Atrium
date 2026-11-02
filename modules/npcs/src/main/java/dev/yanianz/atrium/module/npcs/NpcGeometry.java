package dev.yanianz.atrium.module.npcs;

import java.util.List;
import org.bukkit.Location;
import org.bukkit.util.Vector;

/** Yaw/pitch and line-of-sight math for look-at and /npc select. Pure (no world access). */
final class NpcGeometry {
    private NpcGeometry() {}

    /** Bukkit yaw/pitch (degrees) that make an entity at {@code from} look at {@code to}. */
    static float[] lookAt(Location from, Location to) {
        return lookAt(to.getX() - from.getX(), to.getY() - from.getY(), to.getZ() - from.getZ());
    }

    static float[] lookAt(double dx, double dy, double dz) {
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return new float[] {normalizeYaw(yaw), Math.max(-90f, Math.min(90f, pitch))};
    }

    static float normalizeYaw(float yaw) {
        float value = yaw % 360f;
        if (value >= 180f) {
            value -= 360f;
        }
        if (value < -180f) {
            value += 360f;
        }
        return value;
    }

    /** Cosine of the angle between the view direction and the direction to the target (1 = straight ahead). */
    static double alignment(Location eye, Vector direction, Location target) {
        Vector to = target.toVector().subtract(eye.toVector());
        double length = to.length();
        double viewLength = direction.length();
        if (length < 1e-6 || viewLength < 1e-6) {
            return -1;
        }
        return to.multiply(1 / length).dot(direction.multiply(1 / viewLength));
    }

    /**
     * Index of the best candidate in front of the player, or -1. A candidate must be inside
     * {@code maxDistance}, within the view cone ({@code minAlignment}) and closer than the current best.
     */
    static int select(Location eye, Vector direction, List<Location> candidates, double maxDistance,
            double minAlignment) {
        double maxSquared = maxDistance * maxDistance;
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < candidates.size(); i++) {
            Location candidate = candidates.get(i);
            if (candidate == null) {
                continue;
            }
            double distance = eye.toVector().distanceSquared(candidate.toVector());
            if (distance > maxSquared || distance >= bestDistance) {
                continue;
            }
            if (alignment(eye, direction, candidate) < minAlignment) {
                continue;
            }
            best = i;
            bestDistance = distance;
        }
        return best;
    }
}
