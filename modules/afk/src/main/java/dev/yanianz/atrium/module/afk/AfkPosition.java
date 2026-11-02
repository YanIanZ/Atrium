package dev.yanianz.atrium.module.afk;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.Location;

/** A location snapshot without a live world reference, safe to keep after a player changes worlds. */
record AfkPosition(UUID world, double x, double y, double z, double yaw, double pitch) {
    AfkPosition {
        Objects.requireNonNull(world, "world");
    }

    static AfkPosition of(Location location) {
        if (location.getWorld() == null) {
            throw new IllegalArgumentException("Location has no world");
        }
        return new AfkPosition(location.getWorld().getUID(), location.getX(), location.getY(),
                location.getZ(), location.getYaw(), location.getPitch());
    }

    double horizontalDistance(AfkPosition other) {
        return Math.hypot(x - other.x, z - other.z);
    }

    double verticalDistance(AfkPosition other) {
        return Math.abs(y - other.y);
    }

    /** Largest yaw/pitch change in degrees. */
    double rotationDistance(AfkPosition other) {
        return Math.max(angleDelta(yaw, other.yaw), Math.abs(pitch - other.pitch));
    }

    private static double angleDelta(double first, double second) {
        double delta = (first - second) % 360.0;
        if (delta > 180.0) {
            delta = 360.0 - delta;
        } else if (delta < -180.0) {
            delta = 360.0 + delta;
        }
        return Math.abs(delta);
    }
}
