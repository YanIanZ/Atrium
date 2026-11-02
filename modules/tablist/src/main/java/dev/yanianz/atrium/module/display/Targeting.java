package dev.yanianz.atrium.module.display;

import java.util.List;
import java.util.Locale;

/**
 * World / permission condition used by tab list overrides, sidebar boards and boss bar announcements.
 * Empty world list = every world; empty permission = everyone.
 */
public record Targeting(List<String> worlds, String permission) {
    public static final Targeting EVERYONE = new Targeting(List.of(), "");

    public Targeting {
        worlds = worlds == null ? List.of() : worlds.stream()
                .filter(w -> w != null && !w.isBlank())
                .map(w -> w.trim().toLowerCase(Locale.ROOT))
                .toList();
        permission = permission == null ? "" : permission.trim();
    }

    public boolean matchesWorld(String world) {
        return worlds.isEmpty() || (world != null && worlds.contains(world.toLowerCase(Locale.ROOT)));
    }

    /** {@code hasPermission} is called only when a permission is configured (on the player's thread). */
    public boolean matches(String world, java.util.function.Predicate<String> hasPermission) {
        return matchesWorld(world) && (permission.isEmpty() || hasPermission.test(permission));
    }
}
