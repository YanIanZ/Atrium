package dev.yanianz.atrium.api.proxy;

import java.util.Objects;

/**
 * A message received from another server through the proxy.
 *
 * <p>{@link #payload()} is application data written by the publishing server. It is untrusted text unless
 * the publishing server is fully trusted; never parse it as MiniMessage.
 *
 * @param server origin server name reported by the proxy (authenticated by the envelope MAC)
 * @param topic application topic, e.g. {@code chat:staff}
 * @param timestamp epoch milliseconds written by the origin server
 * @param payload raw application bytes (defensive copy on every access)
 */
public record ProxyMessage(String server, String topic, long timestamp, byte[] payload) {
    public ProxyMessage {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(topic, "topic");
        payload = payload == null ? new byte[0] : payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
