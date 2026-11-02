package dev.yanianz.atrium.module.messaging;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Optional;

/**
 * Cross-server payload of a helpop request. The origin server name is included for display, but the
 * authenticated {@link dev.yanianz.atrium.api.proxy.ProxyMessage#server()} remains authoritative.
 *
 * <p>Every field is untrusted text on the receiving side; callers must insert it with
 * {@code Msg.unparsed}, never MiniMessage or PlaceholderAPI.
 */
record HelpOpPayload(String server, String player, String message) {
    private static final int MAX_SERVER = 128;
    private static final int MAX_PLAYER = 64;
    private static final int MAX_MESSAGE = 2048;

    byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(limit(server, MAX_SERVER));
            out.writeUTF(limit(player, MAX_PLAYER));
            out.writeUTF(limit(message, MAX_MESSAGE));
        }
        return bytes.toByteArray();
    }

    static Optional<HelpOpPayload> decode(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > 32 * 1024) {
            return Optional.empty();
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            String server = in.readUTF();
            String player = in.readUTF();
            String message = in.readUTF();
            if (in.available() != 0 || server.isBlank() || player.isBlank() || message.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new HelpOpPayload(limit(server, MAX_SERVER), limit(player, MAX_PLAYER),
                    limit(message, MAX_MESSAGE)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String limit(String text, int maximum) {
        String value = text == null ? "" : text;
        if (value.length() <= maximum) {
            return value;
        }
        int end = maximum;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }
}
