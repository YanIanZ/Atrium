package dev.yanianz.atrium.module.messaging;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Optional;

/**
 * Cross-server payload of a {@code /bbroadcast}. The origin server and sender are included for display, but the
 * authenticated {@link dev.yanianz.atrium.api.proxy.ProxyMessage#server()} remains authoritative for the origin.
 *
 * <p>Every field is untrusted on the receiving side: it is inserted with {@code Msg.unparsed}, never parsed as
 * MiniMessage. The message is the plain text of the sender's template, so formatting cannot be smuggled in.
 */
record BroadcastPayload(String server, String sender, String message, java.util.List<String> targets) {
    BroadcastPayload {
        targets = targets == null ? java.util.List.of() : java.util.List.copyOf(targets);
    }

    /** Whether this payload is meant for {@code serverName}; an empty target list means every server. */
    boolean targets(String serverName) {
        return targets.isEmpty() || targets.stream().anyMatch(target -> target.equalsIgnoreCase(serverName));
    }

    private static final int MAX_SERVER = 128;
    private static final int MAX_SENDER = 64;
    private static final int MAX_MESSAGE = 2048;
    private static final int MAX_TARGETS = 4096;

    byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(limit(server, MAX_SERVER));
            out.writeUTF(limit(sender, MAX_SENDER));
            out.writeUTF(limit(message, MAX_MESSAGE));
            out.writeUTF(limit(String.join(",", targets), MAX_TARGETS));
        }
        return bytes.toByteArray();
    }

    static Optional<BroadcastPayload> decode(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > 32 * 1024) {
            return Optional.empty();
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            String server = in.readUTF();
            String sender = in.readUTF();
            String message = in.readUTF();
            String targetsText = in.readUTF();
            if (in.available() != 0 || server.isBlank() || sender.isBlank() || message.isBlank()) {
                return Optional.empty();
            }
            java.util.List<String> targets = new java.util.ArrayList<>();
            for (String target : targetsText.split(",")) {
                if (!target.isBlank() && targets.size() < 64) {
                    targets.add(limit(target.strip(), MAX_SERVER));
                }
            }
            return Optional.of(new BroadcastPayload(limit(server, MAX_SERVER), limit(sender, MAX_SENDER),
                    limit(message, MAX_MESSAGE), targets));
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
