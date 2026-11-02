package dev.yanianz.atrium.module.chat;

import dev.yanianz.atrium.api.chat.IgnoreAdminService;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.api.user.UserService;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link IgnoreAdminService} over the chat module's {@code chat:user} component, used by the importer.
 *
 * <p>Writes go through the same atomic component update as {@code /ignore}, but only add entries, so a
 * repeated import stores the same list and an ignore the player added in between is kept. The change is
 * confirmed only after {@link UserService#saveNow(UUID)} wrote it; a failed save therefore fails the
 * import instead of reporting a success that a restart would lose.
 */
final class ChatIgnoreAdminService implements IgnoreAdminService {
    private final UserService users;
    private final UserComponentType<ChatUser> component;

    ChatIgnoreAdminService(UserService users, UserComponentType<ChatUser> component) {
        this.users = users;
        this.component = component;
    }

    @Override
    public CompletableFuture<Integer> importIgnores(UUID owner, Collection<UUID> ignored) {
        if (owner == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("owner is required"));
        }
        Set<UUID> add = sanitize(owner, ignored);
        if (add.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        return users.load(owner).thenCompose(found -> {
            if (found.isEmpty()) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("no Atrium account for " + owner));
            }
            AtriumUser user = found.get();
            AtomicInteger added = new AtomicInteger();
            user.update(component, current -> {
                Set<UUID> before = current.ignored();
                Set<UUID> next = new LinkedHashSet<>(before);
                next.addAll(add);
                added.set(next.size() - before.size());
                return current.withIgnored(next);
            });
            return users.saveNow(owner).thenApply(saved -> added.get());
        });
    }

    /** Entries that can be stored: no {@code null}, no duplicate and never the owner themselves. */
    static Set<UUID> sanitize(UUID owner, Collection<UUID> ignored) {
        Set<UUID> out = new LinkedHashSet<>();
        if (ignored == null) {
            return out;
        }
        for (UUID uuid : ignored) {
            if (uuid != null && !uuid.equals(owner)) {
                out.add(uuid);
            }
        }
        return out;
    }
}
