package dev.yanianz.atrium.module.chat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.event.HoverEventSource;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.jspecify.annotations.Nullable;

/**
 * Wire format for cross-server chat.
 *
 * <pre>
 * byte   version (1 or 2)
 * byte   kind (0 global, 1 staff, 2 private)
 * 16     sender UUID
 * UTF    sender name
 * UTF    target name (empty unless private)
 * UTF    plain message text (fallback and v1 field)
 * --- version 2 only ---
 * UTF    origin server label
 * UTF    rendered line as Adventure JSON (empty when it was too large)
 * byte   mentioned-name count (0-16)
 * UTF    mentioned name (repeated)
 * </pre>
 *
 * <p>Incoming bytes are untrusted even though the envelope is authenticated: another server (or a plugin
 * on it) writes this payload. Every field is therefore length-limited and validated before use, control
 * characters are rejected, the JSON is parsed into a component (a parse failure drops the payload) and
 * only harmless click actions survive. The plain text is never parsed as MiniMessage.
 */
final class CrossServerChat {
    static final int VERSION = 2;
    static final int LEGACY_VERSION = 1;
    /** The proxy module refuses a payload larger than 32 KiB. */
    static final int MAX_PAYLOAD_BYTES = 32 * 1024;
    /** Rendered-line JSON is capped; above this the line is sent without item hovers, then as plain text. */
    static final int MAX_JSON_BYTES = 24 * 1024;
    static final int MAX_TEXT_CHARS = 256;
    static final int MAX_NAME_CHARS = 16;
    static final int MAX_SERVER_CHARS = 64;
    static final int MAX_MENTIONS = 16;

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern SERVER = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    /** A click on a received line may only open a shared snapshot, never run an arbitrary command. */
    private static final Pattern SAFE_COMMAND = Pattern.compile("/chatview [A-Za-z0-9]{12}");
    /** The sender-name hover card suggests a private message; nothing else may be suggested. */
    private static final Pattern SAFE_SUGGEST = Pattern.compile("/msg [A-Za-z0-9_]{1,16} ?");
    private static final UUID ZERO = new UUID(0, 0);
    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();

    private CrossServerChat() {}

    enum Kind { GLOBAL, STAFF, PRIVATE }

    /**
     * @param server   origin server label (empty for a v1 payload)
     * @param line     fully rendered line or message body; {@code null} for v1 and oversized lines
     * @param mentions canonical mentioned player names, validated and de-duplicated
     */
    record Payload(Kind kind, UUID sender, String senderName, String targetName, String text,
            String server, @Nullable Component line, List<String> mentions) {
        Payload {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(sender, "sender");
            Objects.requireNonNull(senderName, "senderName");
            Objects.requireNonNull(targetName, "targetName");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(server, "server");
            mentions = List.copyOf(mentions);
        }

        /** A v1 payload: plain text only, no rendered line. */
        static Payload legacy(Kind kind, UUID sender, String senderName, String targetName, String text) {
            return new Payload(kind, sender, senderName, targetName, text, "", null, List.of());
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
        SERVER,
        JSON,
        MENTIONS,
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
        String json = jsonFor(checked.line());
        List<String> mentions = cleanMentions(checked.mentions());
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(256 + json.length() + checked.text().length());
            try (DataOutputStream out = new DataOutputStream(buffer)) {
                out.writeByte(VERSION);
                out.writeByte(checked.kind().ordinal());
                out.writeLong(checked.sender().getMostSignificantBits());
                out.writeLong(checked.sender().getLeastSignificantBits());
                out.writeUTF(checked.senderName());
                out.writeUTF(checked.targetName());
                out.writeUTF(checked.text());
                out.writeUTF(serverLabel(checked.server()));
                out.writeUTF(json);
                out.writeByte(mentions.size());
                for (String mention : mentions) {
                    out.writeUTF(mention);
                }
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
            throw new InvalidPayloadException(Reason.TOO_LARGE,
                    "payload is larger than " + MAX_PAYLOAD_BYTES + " bytes");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            byte version = in.readByte();
            if (version != VERSION && version != LEGACY_VERSION) {
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
                throw new InvalidPayloadException(Reason.TARGET_NAME,
                        "target name is only valid for private messages");
            }
            String text = readUtf(in, MAX_TEXT_CHARS * 2);
            if (!validText(text)) {
                throw new InvalidPayloadException(Reason.TEXT, "invalid message text");
            }
            String server = "";
            Component line = null;
            List<String> mentions = List.of();
            if (version == VERSION) {
                server = readUtf(in, MAX_SERVER_CHARS);
                if (!validServer(server)) {
                    throw new InvalidPayloadException(Reason.SERVER, "invalid origin server label");
                }
                String json = in.readUTF();
                if (json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
                    throw new InvalidPayloadException(Reason.JSON, "rendered line is larger than the cap");
                }
                if (!json.isEmpty()) {
                    try {
                        line = GSON.deserialize(json);
                    } catch (RuntimeException e) {
                        throw new InvalidPayloadException(Reason.JSON, "rendered line is not valid component JSON");
                    }
                }
                int count = in.readUnsignedByte();
                if (count > MAX_MENTIONS) {
                    throw new InvalidPayloadException(Reason.MENTIONS, "too many mentioned names");
                }
                if (count > 0) {
                    List<String> found = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        String mention = readUtf(in, MAX_NAME_CHARS);
                        if (!validName(mention)) {
                            throw new InvalidPayloadException(Reason.MENTIONS, "invalid mentioned name");
                        }
                        found.add(mention);
                    }
                    mentions = List.copyOf(found);
                }
            }
            if (in.available() != 0) {
                throw new InvalidPayloadException(Reason.TRAILING_BYTES, "unexpected trailing bytes");
            }
            return new Payload(kind, sender, senderName, targetName, text, sanitise(server),
                    line == null ? null : sanitise(line), mentions);
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

    private static String jsonFor(@Nullable Component line) {
        if (line == null) {
            return "";
        }
        String json = GSON.serialize(line);
        if (utf8Length(json) <= MAX_JSON_BYTES) {
            return json;
        }
        String withoutItems = GSON.serialize(withoutItemHovers(line));
        return utf8Length(withoutItems) <= MAX_JSON_BYTES ? withoutItems : "";
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static List<String> cleanMentions(List<String> mentions) {
        if (mentions == null || mentions.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String mention : mentions) {
            if (validName(mention)) {
                unique.add(mention);
                if (unique.size() >= MAX_MENTIONS) {
                    break;
                }
            }
        }
        return List.copyOf(unique);
    }

    private static String sanitise(String server) {
        return validServer(server) ? server : "unknown";
    }

    /** Keeps harmless events only; a received line must not be able to run an arbitrary command. */
    private static Component sanitise(Component input) {
        Component copy = input;
        ClickEvent<?> click = copy.clickEvent();
        ClickEvent<?> safe = safeClick(click);
        if (safe != click) {
            copy = copy.clickEvent(safe);
        }
        List<Component> children = copy.children();
        if (!children.isEmpty()) {
            List<Component> mapped = new ArrayList<>(children.size());
            for (Component child : children) {
                mapped.add(sanitise(child));
            }
            copy = copy.children(mapped);
        }
        return copy;
    }

    private static @Nullable ClickEvent<?> safeClick(@Nullable ClickEvent<?> click) {
        if (click == null) {
            return null;
        }
        if (click.action() == ClickEvent.Action.OPEN_URL
                || click.action() == ClickEvent.Action.COPY_TO_CLIPBOARD) {
            return click;
        }
        if (click.action() == ClickEvent.Action.RUN_COMMAND) {
            return click.payload() instanceof ClickEvent.Payload.Text text
                    && SAFE_COMMAND.matcher(text.value()).matches() ? click : null;
        }
        if (click.action() == ClickEvent.Action.SUGGEST_COMMAND) {
            return click.payload() instanceof ClickEvent.Payload.Text text
                    && (SAFE_COMMAND.matcher(text.value()).matches()
                        || SAFE_SUGGEST.matcher(text.value()).matches()) ? click : null;
        }
        return null;
    }

    private static Component withoutItemHovers(Component input) {
        Component copy = input;
        HoverEvent<?> hover = copy.hoverEvent();
        if (hover != null && hover.action() == HoverEvent.Action.SHOW_ITEM) {
            copy = copy.hoverEvent((HoverEventSource<?>) null);
        }
        List<Component> children = copy.children();
        if (!children.isEmpty()) {
            List<Component> mapped = new ArrayList<>(children.size());
            for (Component child : children) {
                mapped.add(withoutItemHovers(child));
            }
            copy = copy.children(mapped);
        }
        return copy;
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

    private static boolean validServer(String server) {
        return server != null && SERVER.matcher(server).matches();
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
