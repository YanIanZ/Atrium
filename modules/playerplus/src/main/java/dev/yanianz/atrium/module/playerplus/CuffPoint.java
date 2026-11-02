package dev.yanianz.atrium.module.playerplus;

import org.bukkit.Location;

/**
 * The fixed point a cuffed player may not leave. Stored as plain values (not a live {@link Location}) so
 * it can be read from any thread and persisted without keeping a world reference.
 */
public record CuffPoint(String world, double x, double y, double z, String by, long since) {

    public CuffPoint {
        world = world == null ? "" : world;
        by = by == null ? "" : by;
    }

    public static CuffPoint at(Location location, String by) {
        String world = location.getWorld() == null ? "" : location.getWorld().getName();
        return new CuffPoint(world, location.getX(), location.getY(), location.getZ(), by,
                System.currentTimeMillis());
    }
}
