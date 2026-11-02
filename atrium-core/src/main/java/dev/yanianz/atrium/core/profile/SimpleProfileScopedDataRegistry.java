package dev.yanianz.atrium.core.profile;

import dev.yanianz.atrium.api.profile.ProfileScopedData;
import dev.yanianz.atrium.api.profile.ProfileScopedDataRegistry;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.regex.Pattern;

/** Thread-safe {@link ProfileScopedDataRegistry} published by Atrium core. */
public final class SimpleProfileScopedDataRegistry implements ProfileScopedDataRegistry {
    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");

    private final ConcurrentSkipListMap<String, ProfileScopedData> providers = new ConcurrentSkipListMap<>();

    @Override
    public void register(ProfileScopedData data) {
        Objects.requireNonNull(data, "data");
        String id = data.id();
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid profile-scoped data id: " + id);
        }
        ProfileScopedData previous = providers.putIfAbsent(id, data);
        if (previous != null && previous != data) {
            throw new IllegalStateException("Profile-scoped data id already registered: " + id);
        }
    }

    @Override
    public void unregister(ProfileScopedData data) {
        if (data != null) {
            providers.remove(data.id(), data);
        }
    }

    @Override
    public Optional<ProfileScopedData> get(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(providers.get(id));
    }

    @Override
    public Collection<ProfileScopedData> all() {
        return List.copyOf(providers.values());
    }
}
