package dev.yanianz.atrium.module.moderation;

import java.time.Duration;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jspecify.annotations.Nullable;

/**
 * A jail: a spawn point inside a spherical area the jailed player cannot leave, plus the per-jail options
 * edited by {@code /jailedit}.
 *
 * @param release            where released players are put when their own pre-jail location is unknown
 * @param allowedCommands    commands allowed inside this jail; empty means "use the configured list"
 * @param maxSentence        longest sentence this jail allows; {@code null} for no limit
 * @param chatAllowed        whether prisoners may chat
 */
record Jail(String name, String world, double x, double y, double z, float yaw, float pitch, double radius,
        @Nullable ReleaseLocation release, List<String> allowedCommands, @Nullable Duration maxSentence,
        boolean chatAllowed) {

    Jail {
        allowedCommands = List.copyOf(allowedCommands);
    }

    static Jail at(String name, Location location, double radius) {
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("Location has no world");
        }
        return new Jail(name, world.getName(), location.getX(), location.getY(), location.getZ(), location.getYaw(),
                location.getPitch(), radius, null, List.of(), null, true);
    }

    /** Whether a point lies inside the jail area (same world, within {@link #radius()} of the spawn point). */
    boolean contains(String worldName, double px, double py, double pz) {
        if (!world.equals(worldName)) {
            return false;
        }
        double dx = px - x;
        double dy = py - y;
        double dz = pz - z;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    boolean contains(Location location) {
        World w = location.getWorld();
        return w != null && contains(w.getName(), location.getX(), location.getY(), location.getZ());
    }

    /** Spawn point, or {@code null} when the world is not loaded. */
    @Nullable Location location() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x, y, z, yaw, pitch);
    }

    /** Copy with a new spawn point (same name and options). */
    Jail movedTo(Location location) {
        World w = location.getWorld();
        if (w == null) {
            throw new IllegalArgumentException("Location has no world");
        }
        return new Jail(name, w.getName(), location.getX(), location.getY(), location.getZ(), location.getYaw(),
                location.getPitch(), radius, release, allowedCommands, maxSentence, chatAllowed);
    }

    Jail withRadius(double newRadius) {
        return new Jail(name, world, x, y, z, yaw, pitch, newRadius, release, allowedCommands, maxSentence, chatAllowed);
    }

    Jail withRelease(@Nullable ReleaseLocation newRelease) {
        return new Jail(name, world, x, y, z, yaw, pitch, radius, newRelease, allowedCommands, maxSentence, chatAllowed);
    }

    Jail withAllowedCommands(List<String> commands) {
        return new Jail(name, world, x, y, z, yaw, pitch, radius, release, commands, maxSentence, chatAllowed);
    }

    Jail withMaxSentence(@Nullable Duration max) {
        return new Jail(name, world, x, y, z, yaw, pitch, radius, release, allowedCommands, max, chatAllowed);
    }

    Jail withChatAllowed(boolean allowed) {
        return new Jail(name, world, x, y, z, yaw, pitch, radius, release, allowedCommands, maxSentence, allowed);
    }
}
