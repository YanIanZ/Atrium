package dev.yanianz.atrium.module.importer;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Reads an EssentialsX location: Bukkit's serialized map ({@code world}, {@code x}, {@code y}, {@code z},
 * {@code yaw}, {@code pitch}) with an optional {@code world-name}, a legacy comma separated string, or a
 * list. EssentialsX has stored the world as a name and as a UUID across versions, so both are accepted;
 * {@link WorldIndex#resolve(EssLocation)} turns either into a loaded world name.
 */
final class EssentialsLocations {
    private EssentialsLocations() {}

    /** A location whose world is not yet resolved to a loaded world name. */
    record EssLocation(String worldToken, String worldName, double x, double y, double z, float yaw, float pitch) {
        /**
         * The world UUID wins: a world can be renamed, its UUID cannot, so a stale {@code world-name} must not
         * move a home into another world. The name is used when the token is a name, or when the UUID is not
         * a loaded world. A UUID alone cannot be used as a folder name, so it is never stored as a name.
         */
        Optional<String> world(Map<UUID, String> byId) {
            String token = worldToken == null ? "" : worldToken.trim();
            UUID id = parseUuid(token);
            if (id != null && byId.get(id) != null) {
                return Optional.of(byId.get(id));
            }
            if (worldName != null && !worldName.isBlank()) {
                return Optional.of(worldName.trim());
            }
            if (token.isEmpty() || id != null) {
                return Optional.empty();
            }
            return Optional.of(token);
        }

        private static UUID parseUuid(String token) {
            if (token.isEmpty()) {
                return null;
            }
            try {
                return UUID.fromString(token);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** Loaded worlds by UUID and by name, captured on the global thread before the import reads files. */
    record WorldIndex(Map<UUID, String> byId, Set<String> names) {
        static WorldIndex empty() {
            return new WorldIndex(Map.of(), Set.of());
        }

        Optional<StoredLocation> resolve(EssLocation location) {
            if (location == null) {
                return Optional.empty();
            }
            Optional<String> world = location.world(byId);
            if (world.isEmpty()) {
                return Optional.empty();
            }
            try {
                return Optional.of(new StoredLocation(world.get(), location.x(), location.y(), location.z(),
                        location.yaw(), location.pitch()));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }

    static Optional<EssLocation> parse(ConfigurationNode node) {
        if (node == null || node.virtual()) {
            return Optional.empty();
        }
        if (node.isMap()) {
            String world = text(node, "world", "World");
            String worldName = text(node, "world-name", "worldName", "WorldName");
            if (world == null || world.isBlank()) {
                world = worldName;
            }
            Double x = number(text(node, "x", "X"));
            Double y = number(text(node, "y", "Y"));
            Double z = number(text(node, "z", "Z"));
            if (world == null || world.isBlank() || x == null || y == null || z == null) {
                return Optional.empty();
            }
            Double yaw = number(text(node, "yaw", "Yaw"));
            Double pitch = number(text(node, "pitch", "Pitch"));
            return build(world, worldName, x, y, z, yaw, pitch);
        }
        if (node.isList()) {
            var values = node.childrenList();
            if (values.size() >= 6) {
                return build(values.get(0).getString(), null, number(values.get(1).getString()),
                        number(values.get(2).getString()), number(values.get(3).getString()),
                        number(values.get(4).getString()), number(values.get(5).getString()));
            }
            return Optional.empty();
        }
        String text = node.getString();
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        if (text.indexOf(';') >= 0) {
            Optional<StoredLocation> decoded = StoredLocation.decode(text);
            return decoded.map(location -> new EssLocation(location.world(), null, location.x(), location.y(),
                    location.z(), location.yaw(), location.pitch()));
        }
        String[] parts = text.split(",");
        if (parts.length < 4) {
            return Optional.empty();
        }
        Double x = number(parts[1]);
        Double y = number(parts[2]);
        Double z = number(parts[3]);
        Double yaw = parts.length > 4 ? number(parts[4]) : null;
        Double pitch = parts.length > 5 ? number(parts[5]) : null;
        return build(parts[0].trim(), null, x, y, z, yaw, pitch);
    }

    private static Optional<EssLocation> build(String world, String worldName, Double x, Double y, Double z,
            Double yaw, Double pitch) {
        if (world == null || world.isBlank() || x == null || y == null || z == null
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return Optional.empty();
        }
        float safeYaw = yaw == null || !Float.isFinite(yaw.floatValue()) ? 0f : yaw.floatValue();
        float safePitch = pitch == null || !Float.isFinite(pitch.floatValue()) ? 0f : pitch.floatValue();
        return Optional.of(new EssLocation(world.trim(), worldName, x, y, z, safeYaw, safePitch));
    }

    private static String text(ConfigurationNode node, String... names) {
        return CmiNodeLocations.text(node, names);
    }

    private static Double number(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
