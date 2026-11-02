package dev.yanianz.atrium.module.worldtools;

import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Cuboid selection used by the selection wand, {@code /select} and {@code /replaceblock}.
 *
 * <p>Pure: no Bukkit types, so corner ordering, expansion and the volume limit are unit tested. Every
 * operation clamps to the world's build height and refuses a result that would exceed the configured volume.
 */
record Cuboid(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    static final long MAX_VOLUME_HARD_LIMIT = 16_777_216L;

    enum Direction {
        UP, DOWN, NORTH, SOUTH, EAST, WEST, VERT;

        static @Nullable Direction parse(String text) {
            if (text == null) {
                return null;
            }
            return switch (text.strip().toLowerCase(java.util.Locale.ROOT)) {
                case "up" -> UP;
                case "down" -> DOWN;
                case "north" -> NORTH;
                case "south" -> SOUTH;
                case "east" -> EAST;
                case "west" -> WEST;
                case "vert", "vertical" -> VERT;
                default -> null;
            };
        }
    }

    /** One corner, as read from a block. */
    record Point(String world, int x, int y, int z) {}

    /**
     * Direction an {@code expand} without an explicit direction grows towards: the player's aim, with a
     * steep look counting as up/down. Vanilla yaw: 0 = south, 90 = west, 180 = north, 270 = east.
     */
    static Direction facing(float yaw, float pitch) {
        if (pitch >= 45.0f) {
            return Direction.DOWN;
        }
        if (pitch <= -45.0f) {
            return Direction.UP;
        }
        float normalized = ((yaw % 360.0f) + 360.0f) % 360.0f;
        if (normalized >= 45.0f && normalized < 135.0f) {
            return Direction.WEST;
        }
        if (normalized >= 135.0f && normalized < 225.0f) {
            return Direction.NORTH;
        }
        if (normalized >= 225.0f && normalized < 315.0f) {
            return Direction.EAST;
        }
        return Direction.SOUTH;
    }

    Cuboid {
        Objects.requireNonNull(world, "world");
    }

    /** A cuboid from two arbitrary corners (they are ordered here, never by the caller). */
    static Cuboid of(Point first, Point second) {
        if (!first.world().equals(second.world())) {
            throw new IllegalArgumentException("both corners must be in the same world");
        }
        return new Cuboid(first.world(), Math.min(first.x(), second.x()), Math.min(first.y(), second.y()),
                Math.min(first.z(), second.z()), Math.max(first.x(), second.x()), Math.max(first.y(), second.y()),
                Math.max(first.z(), second.z()));
    }

    static boolean sameWorld(Point first, Point second) {
        return first.world().equals(second.world());
    }

    long volume() {
        return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }

    boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** Grows the cube by {@code amount} in one direction, clamped to the world's build height. */
    Cuboid expand(Direction direction, int amount, int worldMinY, int worldMaxY) {
        int step = Math.max(1, Math.min(1_000_000, amount));
        int minY = Math.max(worldMinY, this.minY);
        int maxY = Math.min(worldMaxY, this.maxY);
        return switch (direction) {
            case UP -> new Cuboid(world, minX, minY, minZ, maxX, Math.min(worldMaxY, maxY + step), maxZ);
            case DOWN -> new Cuboid(world, minX, Math.max(worldMinY, minY - step), minZ, maxX, maxY, maxZ);
            case NORTH -> new Cuboid(world, minX, minY, minZ - step, maxX, maxY, maxZ);
            case SOUTH -> new Cuboid(world, minX, minY, minZ, maxX, maxY, maxZ + step);
            case EAST -> new Cuboid(world, minX, minY, minZ, maxX + step, maxY, maxZ);
            case WEST -> new Cuboid(world, minX - step, minY, minZ, maxX, maxY, maxZ);
            // The whole column: useful for "replace everything above and below this area".
            case VERT -> new Cuboid(world, minX, worldMinY, minZ, maxX, worldMaxY, maxZ);
        };
    }

    Cuboid clamp(int worldMinY, int worldMaxY) {
        return new Cuboid(world, minX, Math.max(worldMinY, minY), minZ, maxX, Math.min(worldMaxY, maxY), maxZ);
    }

    /** {@code true} when the volume fits the limit; a limit below 1 means "no limit" up to the hard limit. */
    boolean fits(long limit) {
        long effective = limit < 1 ? MAX_VOLUME_HARD_LIMIT : Math.min(limit, MAX_VOLUME_HARD_LIMIT);
        return volume() <= effective;
    }

    String describe() {
        return minX + "," + minY + "," + minZ + " -> " + maxX + "," + maxY + "," + maxZ + " (" + volume() + ")";
    }

    Optional<String> describeIfFits(long limit) {
        return fits(limit) ? Optional.of(describe()) : Optional.empty();
    }
}
