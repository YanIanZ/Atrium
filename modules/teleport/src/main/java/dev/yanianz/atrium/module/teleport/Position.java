package dev.yanianz.atrium.module.teleport;

import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.World;

/** Three typed coordinates ({@code x y z}), each absolute or relative ({@code ~}). */
record Position(RelativeCoordinate x, RelativeCoordinate y, RelativeCoordinate z) {
    /** Parses three whitespace-separated coordinates, e.g. {@code "~ ~10 -250.5"}. */
    static Optional<Position> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String[] parts = text.trim().split("\\s+");
        if (parts.length != 3) {
            return Optional.empty();
        }
        Optional<RelativeCoordinate> x = RelativeCoordinate.parse(parts[0]);
        Optional<RelativeCoordinate> y = RelativeCoordinate.parse(parts[1]);
        Optional<RelativeCoordinate> z = RelativeCoordinate.parse(parts[2]);
        if (x.isEmpty() || y.isEmpty() || z.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Position(x.get(), y.get(), z.get()));
    }

    /**
     * The target location. Relative parts are added to {@code base}; the world is {@code world} when given,
     * else the base world. Yaw and pitch are kept from the base.
     */
    Location resolve(Location base, World world) {
        return new Location(world == null ? base.getWorld() : world,
                x.resolve(base.getX(), true), y.resolve(base.getY(), false), z.resolve(base.getZ(), true),
                base.getYaw(), base.getPitch());
    }

    /** Pure variant for tests: resolves against plain numbers. */
    double[] resolve(double baseX, double baseY, double baseZ) {
        return new double[] {x.resolve(baseX, true), y.resolve(baseY, false), z.resolve(baseZ, true)};
    }
}
