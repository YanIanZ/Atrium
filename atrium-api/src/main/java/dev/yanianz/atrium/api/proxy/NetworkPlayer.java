package dev.yanianz.atrium.api.proxy;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One player in the cached network snapshot. {@code uuid} is only known when the player is online on this
 * server; the proxy's {@code PlayerList} answers with names only.
 */
public record NetworkPlayer(String name, @Nullable UUID uuid, String server) {
    public NetworkPlayer {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(server, "server");
    }
}
