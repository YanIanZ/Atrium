package dev.yanianz.atrium.core.permission;

import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.permissions.PermissionDefault;

/**
 * Permission registration plus numeric limits ({@code atrium.homes.5}).
 *
 * <p>Limits are read from the player's effective permissions on every call. Nothing is cached, so a
 * rank change from a permission plugin applies immediately.
 */
public final class Permissions {
    private final Set<String> registered = ConcurrentHashMap.newKeySet();

    public void register(String node, PermissionDefault def, String description) {
        register(node, def, description, Map.of());
    }

    public void register(String node, PermissionDefault def, String description, Map<String, Boolean> children) {
        var manager = Bukkit.getPluginManager();
        if (manager.getPermission(node) != null) {
            return;
        }
        manager.addPermission(new Permission(node, description, def, children));
        registered.add(node);
    }

    /** Nodes registered through this instance (for generated documentation). */
    public Set<String> nodes() {
        return Set.copyOf(registered);
    }

    public void unregisterAll() {
        var manager = Bukkit.getPluginManager();
        registered.forEach(manager::removePermission);
        registered.clear();
    }

    /** Highest {@code <prefix>.<n>} the permissible has set to true. */
    public static OptionalInt highestNumeric(Permissible permissible, String prefix) {
        String base = prefix.endsWith(".") ? prefix : prefix + ".";
        int best = Integer.MIN_VALUE;
        for (PermissionAttachmentInfo info : permissible.getEffectivePermissions()) {
            if (!info.getValue()) {
                continue;
            }
            String node = info.getPermission();
            if (node.length() > base.length() && node.regionMatches(true, 0, base, 0, base.length())) {
                String tail = node.substring(base.length());
                try {
                    best = Math.max(best, Integer.parseInt(tail));
                } catch (NumberFormatException ignored) {
                    // not a numeric node
                }
            }
        }
        return best == Integer.MIN_VALUE ? OptionalInt.empty() : OptionalInt.of(best);
    }

    /**
     * Numeric limit with fallback. {@code <prefix>.unlimited} yields {@link Integer#MAX_VALUE}.
     * The highest granted number wins, never less than {@code fallback}.
     */
    public static int limit(Permissible permissible, String prefix, int fallback) {
        String base = prefix.endsWith(".") ? prefix.substring(0, prefix.length() - 1) : prefix;
        if (permissible.hasPermission(base + ".unlimited")) {
            return Integer.MAX_VALUE;
        }
        OptionalInt highest = highestNumeric(permissible, base);
        return highest.isPresent() ? Math.max(fallback, highest.getAsInt()) : fallback;
    }
}
