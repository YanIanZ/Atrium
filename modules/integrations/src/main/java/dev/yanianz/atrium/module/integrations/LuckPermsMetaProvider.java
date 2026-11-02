package dev.yanianz.atrium.module.integrations;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * LuckPerms metadata. LuckPerms' cached data is thread-safe; the event subscription invalidates only
 * Atrium's own snapshot map, so a recalculation is visible to async chat on the next read.
 */
final class LuckPermsMetaProvider implements MetaProvider {
    private final LuckPerms luckPerms;
    private volatile EventSubscription<UserDataRecalculateEvent> subscription;

    LuckPermsMetaProvider() {
        this.luckPerms = LuckPermsProvider.get();
    }

    /**
     * Starts invalidating on recalculation. Called after the cache service is published, so an event fired
     * immediately after subscribing cannot reach a not-yet-assigned field on the LuckPerms thread.
     */
    void subscribe(Plugin plugin, Consumer<UUID> invalidator) {
        subscription = luckPerms.getEventBus().subscribe(plugin, UserDataRecalculateEvent.class,
                event -> invalidator.accept(event.getUser().getUniqueId()));
    }

    @Override
    public String id() {
        return "luckperms";
    }

    @Override
    public String displayName() {
        return "LuckPerms";
    }

    @Override
    public String version() {
        var plugin = Bukkit.getPluginManager().getPlugin("LuckPerms");
        return plugin == null ? "?" : plugin.getPluginMeta().getVersion();
    }

    @Override
    public MetaSnapshot load(UUID player) {
        User user = luckPerms.getUserManager().getUser(player);
        if (user == null) {
            return MetaSnapshot.EMPTY;
        }
        CachedMetaData data = user.getCachedData().getMetaData();
        return new MetaSnapshot(new dev.yanianz.atrium.api.meta.MetaService.Meta(
                data.getPrefix(), data.getSuffix(), data.getPrimaryGroup()), Map.of());
    }

    @Override
    public Optional<String> value(UUID player, String key) {
        User user = luckPerms.getUserManager().getUser(player);
        if (user == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(user.getCachedData().getMetaData().getMetaValue(key));
    }

    @Override
    public void close() {
        EventSubscription<UserDataRecalculateEvent> current = subscription;
        if (current != null) {
            current.close();
        }
    }
}
