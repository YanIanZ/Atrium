package dev.yanianz.atrium.module.portals;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One portal region. Immutable; edits create a copy.
 *
 * @param action     one admin-written action line run through the actions module after the teleport, or null
 * @param permission permission needed to use the portal, or null for everyone
 * @param cost       price of one use, or null to use the configured default cost
 */
record Portal(String name, Cuboid area, PortalTarget target, @Nullable String action, @Nullable String permission,
        @Nullable UUID creator, long createdAt, @Nullable Double cost) {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern PERMISSION = Pattern.compile("[A-Za-z0-9_.-]{1,128}");

    Portal {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(area, "area");
        Objects.requireNonNull(target, "target");
        if (action != null && action.isBlank()) {
            action = null;
        }
        if (permission != null && permission.isBlank()) {
            permission = null;
        }
        if (cost != null && (!Double.isFinite(cost) || cost < 0)) {
            cost = null;
        }
    }

    /** Portals without an own price use the configured default. */
    Portal(String name, Cuboid area, PortalTarget target, @Nullable String action, @Nullable String permission,
            @Nullable UUID creator, long createdAt) {
        this(name, area, target, action, permission, creator, createdAt, null);
    }

    static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    static boolean validPermission(String node) {
        return node != null && PERMISSION.matcher(node).matches();
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    String key() {
        return key(name);
    }

    Portal withTarget(PortalTarget value) {
        return new Portal(name, area, value, action, permission, creator, createdAt, cost);
    }

    Portal withAction(@Nullable String value) {
        return new Portal(name, area, target, value, permission, creator, createdAt, cost);
    }

    Portal withPermission(@Nullable String value) {
        return new Portal(name, area, target, action, value, creator, createdAt, cost);
    }

    Portal withCost(@Nullable Double value) {
        return new Portal(name, area, target, action, permission, creator, createdAt, value);
    }
}
