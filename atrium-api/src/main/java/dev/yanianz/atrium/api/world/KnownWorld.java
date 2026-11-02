package dev.yanianz.atrium.api.world;

import java.util.Objects;
import org.bukkit.World;
import org.jspecify.annotations.Nullable;

/**
 * A world as listed by a {@link WorldProvider}: loaded, or known to the provider and loadable on request.
 *
 * @param name        the world name (as used by {@link org.bukkit.Bukkit#getWorld(String)})
 * @param provider    id of the provider that listed it ({@link WorldProvider#id()})
 * @param loaded      whether the world was loaded when the list was made
 * @param alias       display alias without colour codes (Multiverse alias), empty when there is none
 * @param environment the environment when known, else null (unloaded worlds of some providers)
 */
public record KnownWorld(String name, String provider, boolean loaded, String alias,
        World.@Nullable Environment environment) {
    public KnownWorld {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(provider, "provider");
        alias = alias == null ? "" : alias;
    }

    public static KnownWorld of(String name, String provider, boolean loaded) {
        return new KnownWorld(name, provider, loaded, "", null);
    }

    public boolean hasAlias() {
        return !alias.isEmpty() && !alias.equals(name);
    }
}
