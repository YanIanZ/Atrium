package dev.yanianz.atrium.module.proxy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticated wire format used on the {@code Forward}/{@code ALL} subchannel of the BungeeCord plugin
 * channel.
 *
 * <pre>
 * byte   version
 * UTF    origin server
 * UTF    topic
 * long   timestamp (epoch millis)
 * 16     random nonce
 * int    payload length
 * byte[] payload
 * 32     HMAC-SHA256 over every preceding byte
 * </pre>
 *
 * <p>Decoding verifies the MAC before trusting any header field, so a forged origin or topic is rejected
 * without being parsed. Timestamp and replay checks live in the caller ({@link #decode} for the window,
 * {@link ReplayGuard} for the nonce) so both stay testable with an injected clock.
 */
final class ProxyEnvelope {
    static final byte VERSION = 1;
    static final int MAX_PAYLOAD = 32 * 1024;
    static final int NONCE_BYTES = 16;
    static final int MAC_BYTES = 32;
    private static final int MIN_BYTES = 1 + 2 + 2 + 8 + NONCE_BYTES + 4 + MAC_BYTES;
    private static final int MAX_TEXT_CHARS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private final byte version;
    private final String origin;
    private final String topic;
    private final long timestamp;
    private final byte[] nonce;
    private final byte[] payload;

    private ProxyEnvelope(byte version, String origin, String topic, long timestamp, byte[] nonce, byte[] payload) {
        this.version = version;
        this.origin = origin;
        this.topic = topic;
        this.timestamp = timestamp;
        this.nonce = nonce.clone();
        this.payload = payload.clone();
    }

    /** Builds a fresh envelope with a random 128-bit nonce. */
    static ProxyEnvelope create(String origin, String topic, byte[] payload) {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        return new ProxyEnvelope(VERSION, requireText(origin, "origin"), requireTopic(topic),
                System.currentTimeMillis(), nonce, requirePayload(payload));
    }

    /** Test/constructor variant with an explicit timestamp and nonce. */
    static ProxyEnvelope of(String origin, String topic, long timestamp, byte[] nonce, byte[] payload) {
        if (nonce == null || nonce.length != NONCE_BYTES) {
            throw new IllegalArgumentException("nonce must be exactly " + NONCE_BYTES + " bytes");
        }
        return new ProxyEnvelope(VERSION, requireText(origin, "origin"), requireTopic(topic), timestamp,
                nonce, requirePayload(payload));
    }

    String origin() {
        return origin;
    }

    String topic() {
        return topic;
    }

    long timestamp() {
        return timestamp;
    }

    byte[] nonce() {
        return nonce.clone();
    }

    String nonceHex() {
        return hex(nonce);
    }

    static String hex(byte[] bytes) {
        return HEX.formatHex(bytes);
    }

    byte[] payload() {
        return payload.clone();
    }

    /** Encodes the envelope, appending the HMAC. The result must fit the proxy's signed-short limit. */
    byte[] encode(byte[] secret) {
        return seal(version, origin, topic, timestamp, nonce, payload, secret);
    }

    /** Seals an arbitrary body. Package-private so tests can exercise the version check. */
    static byte[] seal(byte version, String origin, String topic, long timestamp, byte[] nonce, byte[] payload,
            byte[] secret) {
        if (secret == null || secret.length < 16) {
            throw new IllegalArgumentException("secret must be at least 16 bytes");
        }
        byte[] body;
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 + payload.length);
            try (DataOutputStream out = new DataOutputStream(buffer)) {
                out.writeByte(version);
                out.writeUTF(origin);
                out.writeUTF(topic);
                out.writeLong(timestamp);
                out.write(nonce);
                out.writeInt(payload.length);
                out.write(payload);
            }
            body = buffer.toByteArray();
        } catch (IOException e) {
            // ByteArrayOutputStream never throws.
            throw new IllegalStateException("Cannot encode proxy envelope", e);
        }
        byte[] mac = mac(secret, body);
        byte[] result = Arrays.copyOf(body, body.length + MAC_BYTES);
        System.arraycopy(mac, 0, result, body.length, MAC_BYTES);
        return result;
    }

    /**
     * Verifies and decodes an envelope.
     *
     * @param secret shared HMAC key
     * @param now current epoch millis (injected for tests)
     * @param windowMillis accepted absolute clock difference
     * @throws InvalidEnvelopeException with a machine-readable {@link Reason}
     */
    static ProxyEnvelope decode(byte[] data, byte[] secret, long now, long windowMillis) throws InvalidEnvelopeException {
        if (data == null || data.length < MIN_BYTES) {
            throw new InvalidEnvelopeException(Reason.MALFORMED, "message is too short");
        }
        if (secret == null || secret.length < 16) {
            throw new IllegalStateException("proxy shared secret must be at least 16 bytes");
        }
        int bodyLength = data.length - MAC_BYTES;
        byte[] expected = mac(secret, data, 0, bodyLength);
        byte[] actual = Arrays.copyOfRange(data, bodyLength, data.length);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new InvalidEnvelopeException(Reason.MAC, "HMAC mismatch");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data, 0, bodyLength))) {
            byte version = in.readByte();
            if (version != VERSION) {
                throw new InvalidEnvelopeException(Reason.VERSION, "unsupported version " + version);
            }
            String origin = readText(in, "origin");
            String topic = readText(in, "topic");
            long timestamp = in.readLong();
            long skew = Math.abs(now - timestamp);
            if (skew > windowMillis) {
                throw new InvalidEnvelopeException(Reason.TIMESTAMP, "timestamp skew " + skew + " ms");
            }
            byte[] nonce = new byte[NONCE_BYTES];
            in.readFully(nonce);
            int length = in.readInt();
            if (length < 0 || length > MAX_PAYLOAD) {
                throw new InvalidEnvelopeException(Reason.TOO_LARGE, "payload length " + length);
            }
            if (length > in.available()) {
                throw new InvalidEnvelopeException(Reason.MALFORMED, "payload length exceeds message");
            }
            byte[] payload = new byte[length];
            in.readFully(payload);
            if (in.available() != 0) {
                throw new InvalidEnvelopeException(Reason.MALFORMED, "trailing bytes");
            }
            return new ProxyEnvelope(version, origin, topic, timestamp, nonce, payload);
        } catch (IOException e) {
            throw new InvalidEnvelopeException(Reason.MALFORMED, e.getMessage());
        }
    }

    private static byte[] mac(byte[] secret, byte[] data) {
        return mac(secret, data, 0, data.length);
    }

    private static byte[] mac(byte[] secret, byte[] data, int offset, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(data, offset, length);
            return mac.doFinal();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private static String readText(DataInputStream in, String what) throws IOException, InvalidEnvelopeException {
        String text = in.readUTF();
        if (text.isEmpty() || text.length() > MAX_TEXT_CHARS) {
            throw new InvalidEnvelopeException(Reason.MALFORMED, what + " length " + text.length());
        }
        return text;
    }

    static String requireTopic(String topic) {
        if (topic == null) {
            throw new IllegalArgumentException("topic is required");
        }
        String stripped = topic.strip();
        if (stripped.isEmpty() || stripped.length() > 64) {
            throw new IllegalArgumentException("topic must be 1-64 characters");
        }
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.' || c == ':';
            if (!ok) {
                throw new IllegalArgumentException("topic contains an invalid character: " + c);
            }
        }
        return stripped;
    }

    private static String requireText(String text, String what) {
        if (text == null || text.isBlank() || text.length() > MAX_TEXT_CHARS) {
            throw new IllegalArgumentException(what + " must be 1-" + MAX_TEXT_CHARS + " characters");
        }
        return text;
    }

    private static byte[] requirePayload(byte[] payload) {
        if (payload == null) {
            return new byte[0];
        }
        if (payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload is " + payload.length + " bytes, limit is " + MAX_PAYLOAD);
        }
        return payload.clone();
    }

    enum Reason {
        MALFORMED,
        MAC,
        VERSION,
        TIMESTAMP,
        REPLAY,
        TOO_LARGE
    }

    static final class InvalidEnvelopeException extends Exception {
        private static final long serialVersionUID = 1L;
        private final Reason reason;

        InvalidEnvelopeException(Reason reason, String message) {
            super(message == null ? reason.name() : message);
            this.reason = reason;
        }

        Reason reason() {
            return reason;
        }
    }
}
