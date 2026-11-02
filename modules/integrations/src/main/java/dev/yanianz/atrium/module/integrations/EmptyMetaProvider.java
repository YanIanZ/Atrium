package dev.yanianz.atrium.module.integrations;

import java.util.Optional;
import java.util.UUID;

/** Fallback used when neither LuckPerms nor Vault Chat is available. */
final class EmptyMetaProvider implements MetaProvider {
    @Override
    public String id() {
        return "empty";
    }

    @Override
    public String displayName() {
        return "none";
    }

    @Override
    public String version() {
        return "-";
    }

    @Override
    public MetaSnapshot load(UUID player) {
        return MetaSnapshot.EMPTY;
    }

    @Override
    public Optional<String> value(UUID player, String key) {
        return Optional.empty();
    }
}
