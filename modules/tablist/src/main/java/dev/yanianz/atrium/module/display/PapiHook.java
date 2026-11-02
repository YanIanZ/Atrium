package dev.yanianz.atrium.module.display;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

/**
 * The only class that references PlaceholderAPI. It is loaded lazily, and only after
 * {@link TemplateRenderer} checked that the PlaceholderAPI plugin is enabled.
 */
final class PapiHook {
    private PapiHook() {}

    static String resolve(Player player, String token) {
        return PlaceholderAPI.setPlaceholders(player, "%" + token + "%");
    }
}
