package dev.yanianz.atrium.module.integrations;

import org.bukkit.Bukkit;

/** Detection without touching third-party API classes. */
final class MetaAvailability {
    private MetaAvailability() {}

    static boolean luckPerms() {
        return Bukkit.getPluginManager().isPluginEnabled("LuckPerms");
    }

    static boolean vault() {
        return Bukkit.getPluginManager().isPluginEnabled("Vault");
    }
}
