package dev.yanianz.atrium.module.moderation;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jspecify.annotations.Nullable;

/** Where a jailed player should be returned to, stored while the jail is in effect. */
record ReleaseLocation(String world, double x, double y, double z, float yaw, float pitch) {
    /** No known location (for example a player jailed while offline): fall back to the spawn. */
    static final ReleaseLocation UNKNOWN = new ReleaseLocation("", 0, 0, 0, 0, 0);

    static ReleaseLocation of(Location location) {
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("Location has no world");
        }
        return new ReleaseLocation(world.getName(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
    }

    /** The stored location, or {@code null} when its world is no longer loaded. */
    @Nullable Location toLocation() {
        World loaded = Bukkit.getWorld(world);
        return loaded == null ? null : new Location(loaded, x, y, z, yaw, pitch);
    }
}
