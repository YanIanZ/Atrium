package dev.yanianz.atrium.module.importer;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes the base64 vault payload PlayerVaultsX writes to {@code newvaults/<uuid>.yml}.
 *
 * <p>Shape (from PlayerVaultsX 4.4's own storage description): a {@code DataOutputStream} with an
 * {@code int} slot count followed by, per slot, an {@code int} length and that many item bytes. The item
 * bytes are gzip-compressed item NBT, which is exactly what Paper's {@code ItemStack#serializeAsBytes}
 * produces, so they are read with the public Paper API and never with an object stream.
 *
 * <p>Two shapes are refused on purpose: a payload produced by Java object serialization
 * ({@code BukkitObjectOutputStream}, magic {@code AC ED}) would have to be deserialized by class name,
 * which executes code chosen by the file, and a YAML item map is not what PlayerVaultsX writes. A vault
 * that mixes an unreadable slot with good ones is refused as a whole, because a partial vault would lose
 * the items of the refused slots.
 */
final class PvxVaultCodec {
    /** Java object stream magic; also the start of every {@code BukkitObjectOutputStream} payload. */
    private static final int JAVA_STREAM_MAGIC = 0xACED;
    /** gzip magic: item headers written by {@code ItemStack#serializeAsBytes}. */
    private static final int GZIP_MAGIC = 0x1F8B;
    /** PlayerVaultsX vaults never exceed six rows; a larger count means the payload is not one of ours. */
    static final int MAX_SLOTS = 54;

    /** What a chunk of item bytes is. */
    enum Format {
        /** gzip-compressed item NBT; read with {@code ItemStack#deserializeBytes}. */
        PAPER_NBT,
        /** Java object serialization; refused because reading it deserializes arbitrary classes. */
        JAVA_SERIALIZED,
        /** Not an item this importer can read. */
        UNKNOWN
    }

    /**
     * One decoded vault.
     *
     * @param slots   slot count from the payload
     * @param items   item bytes per slot; a {@code null} entry is an empty slot
     * @param refused the format that made this vault unreadable, or {@code null}
     * @param problem why the payload could not be read, or {@code null}
     */
    record Decoded(int slots, List<byte[]> items, Format refused, String problem) {
        Decoded {
            // A null entry is an empty slot, so the list must accept nulls.
            items = items == null ? List.of()
                    : java.util.Collections.unmodifiableList(new ArrayList<>(items));
        }

        boolean ok() {
            return refused == null && problem == null;
        }

        /** Stable English reason used in the report; the message is diagnostics, not user text. */
        String reason() {
            if (refused == Format.JAVA_SERIALIZED) {
                return "vault was written with Java object serialization (BukkitObjectOutputStream); reading it "
                        + "would deserialize arbitrary classes, so it is not imported";
            }
            if (problem != null) {
                return problem;
            }
            return refused == null ? null : "unsupported item format";
        }
    }

    private PvxVaultCodec() {}

    /** Decodes one base64 vault value. A broken payload is a result, never an exception. */
    static Decoded decode(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return new Decoded(0, List.of(), null, "empty vault data");
        }
        if (startsWith(payload, JAVA_STREAM_MAGIC)) {
            return new Decoded(0, List.of(), Format.JAVA_SERIALIZED, null);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            int slots = in.readInt();
            if (slots <= 0 || slots > MAX_SLOTS) {
                return new Decoded(0, List.of(), null,
                        "invalid slot count " + slots + " (expected 1-" + MAX_SLOTS + ")");
            }
            List<byte[]> items = new ArrayList<>(slots);
            Format refused = null;
            for (int slot = 0; slot < slots; slot++) {
                int length = in.readInt();
                if (length < 0 || length > in.available()) {
                    return new Decoded(slots, List.of(), null,
                            "slot " + (slot + 1) + " has an invalid length " + length);
                }
                if (length == 0) {
                    items.add(null);
                    continue;
                }
                byte[] bytes = new byte[length];
                in.readFully(bytes);
                Format format = format(bytes);
                if (format == Format.JAVA_SERIALIZED) {
                    refused = Format.JAVA_SERIALIZED;
                } else if (format == Format.UNKNOWN && refused == null) {
                    refused = Format.UNKNOWN;
                }
                items.add(bytes);
            }
            if (refused != null) {
                return new Decoded(slots, List.of(), refused, null);
            }
            return new Decoded(slots, items, null, null);
        } catch (EOFException e) {
            return new Decoded(0, List.of(), null, "vault data ends in the middle of an entry");
        } catch (IOException e) {
            return new Decoded(0, List.of(), null, "vault data cannot be read: " + e.getMessage());
        }
    }

    /** Classifies one item header. */
    static Format format(byte[] bytes) {
        if (bytes == null || bytes.length < 2) {
            return Format.UNKNOWN;
        }
        if (startsWith(bytes, JAVA_STREAM_MAGIC)) {
            return Format.JAVA_SERIALIZED;
        }
        if (startsWith(bytes, GZIP_MAGIC)) {
            return Format.PAPER_NBT;
        }
        return Format.UNKNOWN;
    }

    private static boolean startsWith(byte[] bytes, int magic) {
        return bytes.length >= 2 && (bytes[0] & 0xFF) == (magic >> 8) && (bytes[1] & 0xFF) == (magic & 0xFF);
    }

    /** Description of the accepted format, used once in the report and in the docs. */
    static String acceptedFormat() {
        return "gzip item NBT per slot (" + Format.PAPER_NBT.name() + ")";
    }
}
