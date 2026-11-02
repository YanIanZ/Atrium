package dev.yanianz.atrium.api.meta;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only player metadata (prefix, suffix, primary group and arbitrary meta values).
 *
 * <p>The service is backed by LuckPerms when available, then by Vault Chat, and finally by empty values.
 * Reads are safe from any thread (chat formatting runs asynchronously). Values are cached per player and
 * invalidated when the permission plugin recalculates that player's data.
 */
public interface MetaService {
    /** Current metadata of a player. Never {@code null}; unknown/offline players get empty values. */
    Meta meta(UUID player);

    /** One LuckPerms meta value (for Vault Chat fallbacks only the common {@code prefix}/{@code suffix} keys are known). */
    Optional<String> value(UUID player, String key);

    record Meta(String prefix, String suffix, String primaryGroup) {
        public static final Meta EMPTY = new Meta("", "", "");

        public Meta {
            prefix = prefix == null ? "" : prefix;
            suffix = suffix == null ? "" : suffix;
            primaryGroup = primaryGroup == null ? "" : primaryGroup;
        }
    }
}
