package dev.yanianz.atrium.module.integrations;

import net.milkbowl.vault.chat.Chat;

/** Only reached when Vault is enabled, so a server without Vault never resolves Vault classes. */
final class VaultStatus {
    private VaultStatus() {}

    static String chatProvider() {
        var registration = org.bukkit.Bukkit.getServicesManager().getRegistration(Chat.class);
        if (registration == null || registration.getProvider() == null) {
            return null;
        }
        Chat chat = registration.getProvider();
        return chat.getName();
    }
}
