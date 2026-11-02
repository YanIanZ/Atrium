package dev.yanianz.atrium.module.teleport;

import dev.yanianz.atrium.api.teleport.TeleportService.TeleportCause;
import java.util.Locale;
import org.bukkit.permissions.Permissible;

/**
 * Per-cause permission to skip the safe-landing check: {@code atrium.teleport.safe.bypass.<cause>}
 * (for example {@code atrium.teleport.safe.bypass.home}). It only applies when no safe spot was found, so
 * a destination that has one still uses it.
 */
final class SafetyBypass {
    static final String PREFIX = "atrium.teleport.safe.bypass";

    private SafetyBypass() {}

    static String node(TeleportCause cause) {
        return PREFIX + "." + cause.name().toLowerCase(Locale.ROOT);
    }

    static boolean allows(Permissible permissible, TeleportCause cause) {
        return permissible != null && permissible.hasPermission(node(cause));
    }
}
