package dev.yanianz.atrium.module.warps;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** A server warp. Immutable. The creator may be null (console or imported warps). */
public record Warp(
        String name,
        String category,
        StoredLocation location,
        @Nullable UUID creator,
        long createdAt,
        String storedPermission) {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern PERMISSION = Pattern.compile("[A-Za-z0-9_.:*\\-]{1,128}");

    public Warp {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(location, "location");
        storedPermission = storedPermission == null ? "" : storedPermission.strip();
    }

    /** Compatibility constructor for a warp without a custom permission (derived from its name). */
    public Warp(String name, String category, StoredLocation location, @Nullable UUID creator, long createdAt) {
        this(name, category, location, creator, createdAt, "");
    }

    /** Letters, digits, '_' and '-', 1 to 32 characters (also used for categories). */
    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** A permission node, or an empty string when the warp uses its derived node. */
    public static boolean validPermission(String permission) {
        return permission != null && PERMISSION.matcher(permission.strip()).matches();
    }

    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public String key() {
        return key(name);
    }

    /** The stored custom permission, or {@code atrium.warp.<name>} when none was set. */
    public String permission() {
        return storedPermission.isEmpty() ? "atrium.warp." + key() : storedPermission;
    }

    /** True when this warp uses the node derived from its name instead of a stored one. */
    public boolean derivedPermission() {
        return storedPermission.isEmpty();
    }

    public Warp renamed(String newName) {
        return new Warp(newName, category, location, creator, createdAt, storedPermission);
    }

    public Warp moved(StoredLocation newLocation, String newCategory) {
        return new Warp(name, newCategory, newLocation, creator, createdAt, storedPermission);
    }

    public Warp withPermission(String newPermission) {
        return new Warp(name, category, location, creator, createdAt, newPermission);
    }
}
