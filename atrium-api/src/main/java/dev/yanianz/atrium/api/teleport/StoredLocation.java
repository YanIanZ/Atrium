package dev.yanianz.atrium.api.teleport;

import java.util.Objects;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * A location that refers to its world by name, so it can be stored while the world is not loaded.
 * Immutable and safe to share between threads.
 */
public record StoredLocation(String world, double x, double y, double z, float yaw, float pitch) {
    public StoredLocation {
        Objects.requireNonNull(world, "world");
        if (world.isBlank()) {
            throw new IllegalArgumentException("world is blank");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("non-finite coordinate");
        }
        if (!Float.isFinite(yaw)) {
            yaw = 0f;
        }
        if (!Float.isFinite(pitch)) {
            pitch = 0f;
        }
        pitch = Math.max(-90f, Math.min(90f, pitch));
    }

    /** Copies a Bukkit location. Throws when it has no world. */
    public static StoredLocation of(Location location) {
        World world = Objects.requireNonNull(location.getWorld(), "location has no world");
        return new StoredLocation(world.getName(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
    }

    /** The Bukkit location, or empty when the world is not loaded. */
    public Optional<Location> toLocation() {
        World loaded = Bukkit.getWorld(world);
        return loaded == null ? Optional.empty() : Optional.of(new Location(loaded, x, y, z, yaw, pitch));
    }

    public int blockX() {
        return (int) Math.floor(x);
    }

    public int blockY() {
        return (int) Math.floor(y);
    }

    public int blockZ() {
        return (int) Math.floor(z);
    }

    /**
     * Compact, locale-independent text form {@code world;x;y;z;yaw;pitch}. The world name is last-split safe:
     * the five numbers are taken from the end, so world names containing {@code ;} still round-trip.
     */
    public String encode() {
        return world + ';' + x + ';' + y + ';' + z + ';' + yaw + ';' + pitch;
    }

    /** Parses {@link #encode()}; empty for null, blank or malformed input. */
    public static Optional<StoredLocation> decode(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String[] parts = text.split(";", -1);
        if (parts.length < 6) {
            return Optional.empty();
        }
        int n = parts.length;
        String world = String.join(";", java.util.Arrays.copyOf(parts, n - 5));
        try {
            return Optional.of(new StoredLocation(world,
                    Double.parseDouble(parts[n - 5]), Double.parseDouble(parts[n - 4]), Double.parseDouble(parts[n - 3]),
                    Float.parseFloat(parts[n - 2]), Float.parseFloat(parts[n - 1])));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
