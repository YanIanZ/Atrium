package dev.yanianz.atrium.module.ranks;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;

/** Direct LuckPerms API bridge. Loaded only after LuckPerms was detected at runtime. */
final class LuckPermsGroupBridge implements GroupBridge {
    private final LuckPerms luckPerms;

    private LuckPermsGroupBridge(LuckPerms luckPerms) {
        this.luckPerms = luckPerms;
    }

    static GroupBridge create() {
        return new LuckPermsGroupBridge(LuckPermsProvider.get());
    }

    @Override
    public CompletableFuture<Void> setGroup(UUID player, String group, Collection<String> managedGroups) {
        Set<String> managed = managedGroups.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        return luckPerms.getUserManager().loadUser(player).thenCompose(user -> {
            removeManagedGroups(user, managed);
            if (group != null && !group.isBlank()) {
                user.data().add(InheritanceNode.builder(group).build());
                user.setPrimaryGroup(group);
            }
            return luckPerms.getUserManager().saveUser(user);
        });
    }

    private static void removeManagedGroups(User user, Set<String> managed) {
        if (managed.isEmpty()) {
            return;
        }
        user.data().clear(node -> node instanceof InheritanceNode inheritance
                && managed.contains(inheritance.getGroupName().toLowerCase(Locale.ROOT)));
    }
}
