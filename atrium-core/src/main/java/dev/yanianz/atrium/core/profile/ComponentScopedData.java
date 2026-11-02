package dev.yanianz.atrium.core.profile;

import dev.yanianz.atrium.api.profile.ProfileScopedData;
import dev.yanianz.atrium.core.user.UserManager;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * {@link ProfileScopedData} backed by one user component, exported and imported by component id through
 * {@link UserManager#exportComponent} and {@link UserManager#importComponent} (the component's codec must be
 * registered, see {@code ModuleContext#codec}).
 */
public final class ComponentScopedData implements ProfileScopedData {
    private final String id;
    private final String componentId;
    private final String description;
    private final UserManager users;

    public ComponentScopedData(String id, String componentId, String description, UserManager users) {
        this.id = Objects.requireNonNull(id, "id");
        this.componentId = Objects.requireNonNull(componentId, "componentId");
        this.description = Objects.requireNonNull(description, "description");
        this.users = Objects.requireNonNull(users, "users");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String description() {
        return description;
    }

    public String componentId() {
        return componentId;
    }

    @Override
    public byte @Nullable [] capture(UUID player) throws Exception {
        return users.exportComponent(player, componentId).orElse(null);
    }

    @Override
    public CompletableFuture<Void> apply(UUID player, byte @Nullable [] data) throws Exception {
        return users.importComponent(player, componentId, data);
    }
}
