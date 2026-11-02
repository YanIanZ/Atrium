package dev.yanianz.atrium.api.proxy;

import java.util.Collection;
import java.util.function.Consumer;
import org.bukkit.entity.Player;

/**
 * Cross-server transport published by the {@code proxy} module.
 *
 * <p>It speaks the standard {@code BungeeCord} plugin-message channel, which both BungeeCord and Velocity
 * implement. Server-to-server publishes use the {@code Forward}/{@code ALL} subchannel with an
 * {@code atrium:<topic>} subchannel name and an authenticated envelope: version, origin, topic, timestamp,
 * random nonce, payload and an HMAC-SHA256 over all of them. Receivers reject a wrong MAC, a timestamp
 * outside the trusted window, a replayed nonce, an unknown version and oversized payloads.
 *
 * <p>All methods are safe to call from any thread. {@link #connect(Player, String)} is applied on the
 * player's own region thread.
 */
public interface ProxyService {
    /** Name the proxy knows this server by, or {@code "unknown"} until the proxy answered. */
    String serverName();

    /**
     * Asks the proxy to move the player to another server.
     *
     * @return {@code true} when the request was handed to the proxy; {@code false} for an invalid name or
     *     a player who is no longer online
     */
    boolean connect(Player player, String server);

    /**
     * Publishes application bytes to the same topic on every server in the network (including this one,
     * whose own echo is ignored).
     *
     * @throws IllegalArgumentException when the topic is invalid or the payload exceeds 32 KiB
     */
    void publish(String topic, byte[] payload);

    /**
     * Receives messages of one topic. Subscribers run on an async (non-region) scheduler thread, so they
     * must schedule any world or entity access. Close the returned subscription to stop receiving.
     */
    Subscription subscribe(String topic, Consumer<ProxyMessage> consumer);

    /** Cached snapshot of all players the proxy reported, sorted by server and name. */
    Collection<NetworkPlayer> networkPlayers();

    /** Cached server names reported by the proxy, sorted. */
    Collection<String> servers();

    /** Handle returned by {@link #subscribe(String, Consumer)}. */
    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
