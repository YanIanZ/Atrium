package dev.yanianz.atrium.module.seen;

import org.bukkit.Location;

/**
 * Where a player last logged out. Immutable; {@link #NONE} when unknown.
 *
 * @param loggedOutAt epoch milliseconds
 */
public record LogoutLocation(String world, double x, double y, double z, float yaw, float pitch, long loggedOutAt) {
    public static final LogoutLocation NONE = new LogoutLocation("", 0, 0, 0, 0, 0, 0);

    public LogoutLocation {
        world = world == null ? "" : world;
    }

    public boolean isKnown() {
        return !world.isEmpty();
    }

    static LogoutLocation of(Location location, long now) {
        return location.getWorld() == null ? NONE : new LogoutLocation(location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch(), now);
    }
}
