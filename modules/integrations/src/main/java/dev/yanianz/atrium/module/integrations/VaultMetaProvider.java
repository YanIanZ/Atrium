package dev.yanianz.atrium.module.integrations;

import dev.yanianz.atrium.api.meta.MetaService;
import java.util.Optional;
import java.util.UUID;
import net.milkbowl.vault.chat.Chat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Vault Chat fallback. Vault has no recalculation event, so the provider is read on the player's own
 * region thread (join/refresh) and the cached snapshot is used by async readers. Once cached, a chat
 * formatter can read it from any thread.
 */
final class VaultMetaProvider implements MetaProvider {
    private final Chat chat;
    private final String version;

    VaultMetaProvider(Chat chat) {
        this.chat = chat;
        var vault = Bukkit.getPluginManager().getPlugin("Vault");
        this.version = vault == null ? "?" : vault.getPluginMeta().getVersion();
    }

    @Override
    public String id() {
        return "vault";
    }

    @Override
    public String displayName() {
        return "Vault Chat";
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public @Nullable MetaSnapshot load(UUID player) {
        Player online = Bukkit.getPlayer(player);
        if (online == null || !Bukkit.isOwnedByCurrentRegion(online)) {
            // Never call a permissions plugin from an arbitrary async thread just because the cache missed.
            // Returning null (not EMPTY) tells the cache to answer empty without storing that answer.
            return null;
        }
        String prefix = safe(chat.getPlayerPrefix(online));
        String suffix = safe(chat.getPlayerSuffix(online));
        String group = safe(chat.getPrimaryGroup(online));
        return new MetaSnapshot(new MetaService.Meta(prefix, suffix, group), java.util.Map.of());
    }

    @Override
    public Optional<String> value(UUID player, String key) {
        Player online = Bukkit.getPlayer(player);
        if (online == null || !Bukkit.isOwnedByCurrentRegion(online)) {
            return Optional.empty();
        }
        return Optional.ofNullable(chat.getPlayerInfoString(online, key, null));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    static boolean available() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            return false;
        }
        return Bukkit.getServicesManager().getRegistration(Chat.class) != null;
    }

    static VaultMetaProvider create() {
        var registration = Bukkit.getServicesManager().getRegistration(Chat.class);
        if (registration == null || registration.getProvider() == null) {
            throw new IllegalStateException("Vault is enabled without a Chat provider");
        }
        return new VaultMetaProvider(registration.getProvider());
    }
}
