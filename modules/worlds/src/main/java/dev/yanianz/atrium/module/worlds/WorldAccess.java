package dev.yanianz.atrium.module.worlds;

import java.util.Locale;
import org.bukkit.permissions.Permissible;

/**
 * Permission rules of /wtp. {@code atrium.wtp} opens the command; per world, {@code atrium.wtp.world.<world>}
 * (lower case) decides when it is set either way (an explicit deny always wins). When
 * {@code require-world-permission} is on, a world without its own node needs {@code atrium.wtp.world.*}.
 */
final class WorldAccess {
    static final String PERM_WTP = "atrium.wtp";
    static final String PERM_OTHERS = "atrium.wtp.others";
    static final String PERM_PROFILE = "atrium.wtp.profile";
    static final String PERM_WORLD_PREFIX = "atrium.wtp.world.";
    static final String PERM_WORLD_ALL = "atrium.wtp.world.*";

    /** What the rules need to know about the sender; {@link #of(Permissible)} adapts Bukkit. */
    interface Perms {
        boolean has(String node);

        boolean isSet(String node);
    }

    private WorldAccess() {}

    static Perms of(Permissible permissible) {
        return new Perms() {
            @Override
            public boolean has(String node) {
                return permissible.hasPermission(node);
            }

            @Override
            public boolean isSet(String node) {
                return permissible.isPermissionSet(node);
            }
        };
    }

    static String worldNode(String world) {
        return PERM_WORLD_PREFIX + world.toLowerCase(Locale.ROOT);
    }

    /** Whether the sender may /wtp into the world (the command permission itself is checked by the command). */
    static boolean mayUse(Perms perms, String world, boolean requireWorldPermission) {
        String node = worldNode(world);
        if (perms.isSet(node)) {
            return perms.has(node);
        }
        return !requireWorldPermission || perms.has(PERM_WORLD_ALL);
    }
}
