package dev.yanianz.atrium.module.dialogs;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

/**
 * The only class that references PlaceholderAPI. Loaded lazily, and only after {@link DialogsModule} checked
 * that the plugin is enabled, so a server without it never resolves these types.
 */
final class DialogsPlaceholderHook {
    private DialogsPlaceholderHook() {}

    static String resolve(Player viewer, String token) {
        return PlaceholderAPI.setPlaceholders(viewer, "%" + token + "%");
    }
}
