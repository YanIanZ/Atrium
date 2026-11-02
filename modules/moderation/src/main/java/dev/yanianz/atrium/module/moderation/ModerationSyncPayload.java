package dev.yanianz.atrium.module.moderation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Wire format of the {@code moderation} proxy topic. Written by one backend server and read by the others;
 * every field is treated as untrusted, so {@link #decode(byte[], long)} validates the version, the kind, the
 * account id, the text lengths and both timestamps before anything is applied.
 *
 * <p>Layout: {@code version:byte, action:UTF, uuid:UTF (may be empty), name:UTF, reason:UTF, actor:UTF,
 * created:long, expires:long (0 = permanent), ip:UTF (may be empty)}.
 */
final class ModerationSyncPayload {
    static final byte VERSION = 1;
    static final int MAX_BYTES = 4096;
    private static final long CLOCK_SKEW_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final long MAX_FUTURE_MILLIS = Duration.ofDays(365L * 100).toMillis();

    private ModerationSyncPayload() {}

    enum Action {
        BAN,
        UNBAN,
        IPBAN,
        UNIPBAN,
        MUTE,
        UNMUTE,
        KICK;

        /** Account actions are addressed to one uuid; IP actions carry an address instead. */
        boolean needsUuid() {
            return this != IPBAN && this != UNIPBAN;
        }

        boolean needsIp() {
            return this == IPBAN || this == UNIPBAN;
        }
    }

    /**
     * One decoded change.
     *
     * @param created epoch millis at the origin server
     * @param expires epoch millis, or {@code 0} for a permanent punishment
     * @param ip      address or CIDR range for IP actions, otherwise {@code null}
     */
    record Payload(
            Action action,
            @Nullable UUID uuid,
            String name,
            String reason,
            String actor,
            long created,
            long expires,
            @Nullable String ip) {

        boolean permanent() {
            return expires == 0;
        }

        /** An expired change has no effect on a receiver, but is still a valid message. */
        boolean isExpired(long now) {
            return expires != 0 && expires <= now;
        }
    }

    static byte[] encode(Payload payload) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeByte(VERSION);
            out.writeUTF(payload.action().name());
            out.writeUTF(payload.uuid() == null ? "" : payload.uuid().toString());
            out.writeUTF(payload.name());
            out.writeUTF(payload.reason());
            out.writeUTF(payload.actor());
            out.writeLong(payload.created());
            out.writeLong(payload.expires());
            out.writeUTF(payload.ip() == null ? "" : payload.ip());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot encode a moderation sync payload", e);
        }
        byte[] bytes = buffer.toByteArray();
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("moderation sync payload is " + bytes.length + " bytes");
        }
        return bytes;
    }

    static Payload decode(byte[] data, long now) throws InvalidPayloadException {
        if (data == null || data.length == 0 || data.length > MAX_BYTES) {
            throw new InvalidPayloadException(Reason.SIZE);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int version = in.readUnsignedByte();
            if (version != VERSION) {
                throw new InvalidPayloadException(Reason.VERSION);
            }
            Action action;
            try {
                action = Action.valueOf(in.readUTF().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new InvalidPayloadException(Reason.ACTION);
            }
            String uuidText = in.readUTF();
            String name = in.readUTF();
            String reason = in.readUTF();
            String actor = in.readUTF();
            long created = in.readLong();
            long expires = in.readLong();
            String ipText = in.readUTF();
            if (in.available() != 0) {
                throw new InvalidPayloadException(Reason.SIZE);
            }
            UUID uuid = null;
            if (!uuidText.isEmpty()) {
                try {
                    uuid = UUID.fromString(uuidText);
                } catch (IllegalArgumentException e) {
                    throw new InvalidPayloadException(Reason.UUID);
                }
            }
            if (action.needsUuid() && uuid == null) {
                throw new InvalidPayloadException(Reason.UUID);
            }
            if (!validText(name, 64) || !validText(reason, 512) || !validText(actor, 64)) {
                throw new InvalidPayloadException(Reason.TEXT);
            }
            String ip = normalizeIp(ipText, action);
            validateTimes(created, expires, now);
            return new Payload(action, uuid, name, reason, actor, created, expires, ip);
        } catch (IOException e) {
            throw new InvalidPayloadException(Reason.SIZE);
        }
    }

    private static String normalizeIp(String text, Action action) throws InvalidPayloadException {
        if (text.isEmpty()) {
            if (action.needsIp()) {
                throw new InvalidPayloadException(Reason.IP);
            }
            return null;
        }
        Optional<String> normalized = IpAddresses.normalizeRange(text);
        if (normalized.isEmpty()) {
            throw new InvalidPayloadException(Reason.IP);
        }
        return normalized.get();
    }

    private static void validateTimes(long created, long expires, long now) throws InvalidPayloadException {
        // A little clock skew is tolerated; a message from the far future is not trusted.
        if (created <= 0 || created > now + CLOCK_SKEW_MILLIS) {
            throw new InvalidPayloadException(Reason.TIME);
        }
        if (expires < 0 || (expires != 0 && expires <= created) || expires > now + MAX_FUTURE_MILLIS) {
            throw new InvalidPayloadException(Reason.TIME);
        }
    }

    private static boolean validText(String text, int max) {
        if (text.length() > max) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // No control characters and no section sign: this text goes to logs and to admins.
            if (c < 0x20 || c == '\u00a7' || c == '\u007f') {
                return false;
            }
        }
        return true;
    }

    enum Reason {
        SIZE,
        VERSION,
        ACTION,
        UUID,
        TEXT,
        IP,
        TIME
    }

    static final class InvalidPayloadException extends Exception {
        private static final long serialVersionUID = 1L;
        private final Reason reason;

        InvalidPayloadException(Reason reason) {
            super(reason.name(), null, false, false);
            this.reason = reason;
        }

        Reason reason() {
            return reason;
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }
}
