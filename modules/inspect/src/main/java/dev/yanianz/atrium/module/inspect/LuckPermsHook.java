package dev.yanianz.atrium.module.inspect;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.util.Tristate;
import org.bukkit.Bukkit;

/**
 * LuckPerms bridge for offline permission checks. The class is only loaded after
 * {@link #available()} returned true, so a server without LuckPerms never resolves its API types.
 */
final class LuckPermsHook {
    private LuckPermsHook() {}

    static boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("LuckPerms");
    }

    static CompletableFuture<PermissionResult> check(UUID uuid, String node) {
        LuckPerms luckPerms;
        try {
            luckPerms = LuckPermsProvider.get();
        } catch (IllegalStateException e) {
            return CompletableFuture.failedFuture(e);
        }
        return luckPerms.getUserManager().loadUser(uuid).thenApply(user -> result(user, node));
    }

    /** Resolves a name LuckPerms knows even when Atrium never saw the player. */
    static CompletableFuture<Optional<UUID>> lookupUniqueId(String name) {
        LuckPerms luckPerms;
        try {
            luckPerms = LuckPermsProvider.get();
        } catch (IllegalStateException e) {
            return CompletableFuture.failedFuture(e);
        }
        return luckPerms.getUserManager().lookupUniqueId(name).thenApply(Optional::ofNullable);
    }

    private static PermissionResult result(User user, String node) {
        var permissionData = user.getCachedData().getPermissionData();
        var result = permissionData.queryPermission(node);
        List<String> groups = user.getInheritedGroups(permissionData.getQueryOptions()).stream()
                .map(Group::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        Tristate value = result.result();
        boolean explicit = result.node() != null;
        return new PermissionResult(value.asBoolean(), explicit, "LuckPerms (" + user.getPrimaryGroup() + ")", groups);
    }
}
