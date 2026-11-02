package dev.yanianz.atrium.module.ranks;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Group backend abstraction. Keeping LuckPerms types out of this interface means the ranks module can
 * load even when LuckPerms is not installed (the implementation class is only touched when it is).
 */
interface GroupBridge {
    /**
     * Removes every managed ladder group, grants {@code group} (when not blank) and saves the user.
     * Unrelated LuckPerms groups are left untouched.
     */
    CompletableFuture<Void> setGroup(UUID player, String group, Collection<String> managedGroups);
}
