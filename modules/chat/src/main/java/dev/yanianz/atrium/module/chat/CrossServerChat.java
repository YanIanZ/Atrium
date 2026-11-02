package dev.yanianz.atrium.module.chat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Wire format for cross-server chat.
 *
 * <pre>
 * byte   version (1)
 * byte   kind (0 global, 1 staff, 2 private)
 * 16     sender UUID
 * UTF    sender name
 * UTF    target name (empty unless private)
 * UTF    plain message text
 * </pre>
 *
 * <p>Incoming bytes are untrusted even though the envelope is authenticated: another server (or a plugin
 * on it) writes this payload. Every field is therefore length-limited and validated before use, control
 * characters are rejected and the text is never parsed as MiniMessage. A payload larger than
 * {@link #MAX_PAYLOAD_BYTES} is rejected before it can allocate anything significant.
 */
final class CrossServerChat {
    static final int VERSION = 1;
    static final int MAX_PAYLOAD_BYTES = 4096;
    static final int MAX_TEXT_CHARS = 256;
    static final int MAX_NAME_CHARS = 16;

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern SERVER = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final UUID ZERO = new UUID(0, 0);

    private CrossServerChat() {}

    enum Kind { GLOBAL, STAFF, PRIVATE }

    record Payload(Kind kind, UUID sender, String senderName, String targetName, String text) {
        Payload {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(sender, "sender");
            Objects.requireNonNull(senderName, "senderName");
            Objects.requireNonNull(targetName, "targetName");
            Objects.requireNonNull(text, "text");
        }
    }

    /** Reason a payload was rejected; stable ids for tests and logs. */
    enum Reason {
        EMPTY,
        TOO_LARGE,
        VERSION,
        KIND,
        SENDER,
        SENDER_NAME,
        TARGET_NAME,
        TEXT,
        TRAILING_BYTES,
        MALFORMED
    }

    static final class InvalidPayloadException extends Exception {
        private static final long serialVersionUID = 1L;

        private final Reason reason;

        InvalidPayloadException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        Reason reason() {
            return reason;
        }
    }

    static byte[] encode(Payload payload) {
        Payload checked = validate(payload);
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 + checked.text().length());
            try (DataOutputStream out = new DataOutputStream(buffer)) {
                out.writeByte(VERSION);
                out.writeByte(checked.kind().ordinal());
                out.writeLong(checked.sender().getMostSignificantBits());
                out.writeLong(checked.sender().getLeastSignificantBits());
                out.writeUTF(checked.senderName());
                out.writeUTF(checked.targetName());
                out.writeUTF(checked.text());
            }
            byte[] result = buffer.toByteArray();
            if (result.length > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("Cross-server chat payload is too large after encoding");
            }
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot encode cross-server chat payload", e);
        }
    }

    static Payload decode(byte[] raw) throws InvalidPayloadException {
        if (raw == null || raw.length == 0) {
            throw new InvalidPayloadException(Reason.EMPTY, "payload is empty");
        }
        if (raw.length > MAX_PAYLOAD_BYTES) {
            throw new InvalidPayloadException(Reason.TOO_LARGE, "payload is larger than " + MAX_PAYLOAD_BYTES + " bytes");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            byte version = in.readByte();
            if (version != VERSION) {
                throw new InvalidPayloadException(Reason.VERSION, "unsupported version " + version);
            }
            byte kindByte = in.readByte();
            Kind kind;
            if (kindByte == 0) {
                kind = Kind.GLOBAL;
            } else if (kindByte == 1) {
                kind = Kind.STAFF;
            } else if (kindByte == 2) {
                kind = Kind.PRIVATE;
            } else {
                throw new InvalidPayloadException(Reason.KIND, "unknown kind " + kindByte);
            }
            long most = in.readLong();
            long least = in.readLong();
            UUID sender = new UUID(most, least);
            if (ZERO.equals(sender)) {
                throw new InvalidPayloadException(Reason.SENDER, "sender UUID is missing");
            }
            String senderName = readUtf(in, MAX_NAME_CHARS);
            if (!validName(senderName)) {
                throw new InvalidPayloadException(Reason.SENDER_NAME, "invalid sender name");
            }
            String targetName = readUtf(in, MAX_NAME_CHARS);
            if (kind == Kind.PRIVATE) {
                if (!validName(targetName)) {
                    throw new InvalidPayloadException(Reason.TARGET_NAME, "invalid target name");
                }
            } else if (!targetName.isEmpty()) {
                throw new InvalidPayloadException(Reason.TARGET_NAME, "target name is only valid for private messages");
            }
            String text = readUtf(in, MAX_TEXT_CHARS * 2);
            if (!validText(text)) {
                throw new InvalidPayloadException(Reason.TEXT, "invalid message text");
            }
            if (in.available() != 0) {
                throw new InvalidPayloadException(Reason.TRAILING_BYTES, "unexpected trailing bytes");
            }
            return new Payload(kind, sender, senderName, targetName, text);
        } catch (EOFException e) {
            throw new InvalidPayloadException(Reason.MALFORMED, "payload is truncated");
        } catch (IOException e) {
            throw new InvalidPayloadException(Reason.MALFORMED, "payload is not valid UTF-8 data");
        }
    }

    private static Payload validate(Payload payload) {
        Objects.requireNonNull(payload, "payload");
        if (ZERO.equals(payload.sender())) {
            throw new IllegalArgumentException("sender UUID is missing");
        }
        if (!validName(payload.senderName())) {
            throw new IllegalArgumentException("invalid sender name");
        }
        if (payload.kind() == Kind.PRIVATE) {
            if (!validName(payload.targetName())) {
                throw new IllegalArgumentException("invalid target name");
            }
        } else if (!payload.targetName().isEmpty()) {
            throw new IllegalArgumentException("target name is only valid for private messages");
        }
        if (!validText(payload.text())) {
            throw new IllegalArgumentException("invalid message text");
        }
        return payload;
    }

    private static String readUtf(DataInputStream in, int maxChars) throws IOException, InvalidPayloadException {
        String value = in.readUTF();
        if (value.length() > maxChars) {
            throw new InvalidPayloadException(Reason.MALFORMED, "field is longer than the maximum");
        }
        return value;
    }

    /** Player name pattern used for both outgoing targets and incoming payloads. */
    static boolean validName(@Nullable String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** Server labels from the authenticated envelope are still sanitised before display. */
    static String serverLabel(@Nullable String server) {
        if (server == null || server.isBlank()) {
            return "unknown";
        }
        String stripped = server.strip();
        return SERVER.matcher(stripped).matches() ? stripped : "unknown";
    }

    /**
     * Normalises text before it leaves this server: colour codes and control characters are removed,
     * whitespace is collapsed and the result is limited by code points (never splitting a surrogate pair).
     */
    static String outgoingText(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String cleaned = clean(raw);
        return limit(cleaned, MAX_TEXT_CHARS).strip();
    }

    private static boolean validText(String text) {
        if (text.isBlank() || text.codePointCount(0, text.length()) > MAX_TEXT_CHARS) {
            return false;
        }
        return text.codePoints().noneMatch(cp -> Character.isISOControl(cp) || cp == 0x00A7);
    }

    private static String clean(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        boolean space = false;
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0x00A7) {
                // Drop the section sign and the single formatting code that follows it.
                if (i < raw.length()) {
                    i += Character.charCount(raw.codePointAt(i));
                }
                continue;
            }
            if (Character.isISOControl(cp) || Character.isWhitespace(cp)) {
                space = out.length() > 0;
                continue;
            }
            if (space && out.length() > 0) {
                out.append(' ');
            }
            space = false;
            out.appendCodePoint(cp);
        }
        return out.toString();
    }

    private static String limit(String text, int maxCodePoints) {
        if (text.codePointCount(0, text.length()) <= maxCodePoints) {
            return text;
        }
        int end = text.offsetByCodePoints(0, maxCodePoints);
        return text.substring(0, end);
    }

    /** Lower-cases a name for comparisons without touching the stored spelling. */
    static String key(@Nullable String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
