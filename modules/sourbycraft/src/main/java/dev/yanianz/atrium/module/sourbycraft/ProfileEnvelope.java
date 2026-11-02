package dev.yanianz.atrium.module.sourbycraft;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The bytes Atrium stores in one SourbyCraft profile snapshot: every profile-scoped data entry, tagged
 * with its id and format version. Layout: magic {@code ATPF}, format, entry count, then per entry id,
 * version and length ({@code -1} = empty state) followed by the data. Bounded by SourbyCraft's adapter
 * limit ({@link #MAX_BYTES}); decoding checks every length before allocating.
 *
 * <p>The reserved entry {@link #OWNER_ENTRY} (not a valid data id, so it never collides with a provider)
 * holds the id of the profile the data belongs to. Readers that do not know it ignore it, so adding it did
 * not change {@link #FORMAT} (which is also the adapter version SourbyCraft stores the bytes under).
 */
final class ProfileEnvelope {
    /** SourbyCraft stores at most 2 MiB per adapter. */
    static final int MAX_BYTES = 2 * 1024 * 1024;
    static final int MAGIC = 0x41545046;
    static final int FORMAT = 1;
    static final int MAX_ENTRIES = 64;
    /** Entry id of the owning profile id (16 bytes, most then least significant bits). */
    static final String OWNER_ENTRY = "#owner";
    static final int OWNER_VERSION = 1;

    /** One data entry; {@code data == null} means "empty state". */
    record Entry(int version, byte @Nullable [] data) {
        Entry {
            if (version < 0) {
                throw new IllegalArgumentException("negative version");
            }
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Entry other && other.version == version && java.util.Arrays.equals(other.data, data);
        }

        @Override
        public int hashCode() {
            return 31 * version + java.util.Arrays.hashCode(data);
        }
    }

    /** Thrown when the encoded state exceeds {@link #MAX_BYTES}. */
    static final class TooLargeException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        TooLargeException(String message) {
            super(message);
        }
    }

    private ProfileEnvelope() {}

    /** Encodes entries sorted by id (deterministic output). */
    static byte[] encode(Map<String, Entry> entries) throws IOException {
        Objects.requireNonNull(entries, "entries");
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Too many profile data entries: " + entries.size());
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeByte(FORMAT);
            out.writeShort(entries.size());
            for (Map.Entry<String, Entry> e : new TreeMap<>(entries).entrySet()) {
                byte[] data = e.getValue().data();
                if (data != null && data.length > MAX_BYTES) {
                    throw new TooLargeException("Profile data '" + e.getKey() + "' is " + data.length
                            + " bytes; SourbyCraft stores at most " + MAX_BYTES + " per adapter");
                }
                out.writeUTF(e.getKey());
                out.writeInt(e.getValue().version());
                if (data == null) {
                    out.writeInt(-1);
                } else {
                    out.writeInt(data.length);
                    out.write(data);
                }
                if (bytes.size() > MAX_BYTES) {
                    throw new TooLargeException("Atrium profile data exceeds " + MAX_BYTES + " bytes");
                }
            }
        }
        return bytes.toByteArray();
    }

    /** {@code entries} plus the owner entry (when {@code owner} is not null), for {@link #encode}. */
    static Map<String, Entry> withOwner(Map<String, Entry> entries, @Nullable UUID owner) {
        Map<String, Entry> result = new LinkedHashMap<>(entries);
        result.remove(OWNER_ENTRY);
        if (owner != null) {
            ByteBuffer bytes = ByteBuffer.allocate(16);
            bytes.putLong(owner.getMostSignificantBits()).putLong(owner.getLeastSignificantBits());
            result.put(OWNER_ENTRY, new Entry(OWNER_VERSION, bytes.array()));
        }
        return result;
    }

    /**
     * The owning profile recorded in decoded entries, or null when there is none (data written before owners
     * were recorded).
     *
     * @throws IOException when the owner entry is malformed
     */
    static @Nullable UUID owner(Map<String, Entry> entries) throws IOException {
        Entry entry = entries.get(OWNER_ENTRY);
        if (entry == null) {
            return null;
        }
        byte[] data = entry.data();
        if (entry.version() != OWNER_VERSION || data == null || data.length != 16) {
            throw new IOException("Corrupt owner entry in Atrium profile data");
        }
        ByteBuffer bytes = ByteBuffer.wrap(data);
        return new UUID(bytes.getLong(), bytes.getLong());
    }

    /** Decodes {@link #encode} output, in stored order. */
    static Map<String, Entry> decode(byte[] raw) throws IOException {
        if (raw == null) {
            throw new IOException("No profile data");
        }
        if (raw.length > MAX_BYTES) {
            throw new IOException("Profile data of " + raw.length + " bytes exceeds " + MAX_BYTES);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            if (in.readInt() != MAGIC) {
                throw new IOException("Not Atrium profile data (bad magic)");
            }
            int format = in.readUnsignedByte();
            if (format != FORMAT) {
                throw new IOException("Unsupported Atrium profile data format " + format);
            }
            int count = in.readUnsignedShort();
            if (count > MAX_ENTRIES) {
                throw new IOException("Too many profile data entries: " + count);
            }
            Map<String, Entry> entries = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                String id = in.readUTF();
                int version = in.readInt();
                int length = in.readInt();
                if (version < 0 || length < -1 || length > in.available()) {
                    throw new IOException("Corrupt profile data entry '" + id + "'");
                }
                byte[] data = null;
                if (length >= 0) {
                    data = new byte[length];
                    in.readFully(data);
                }
                if (entries.put(id, new Entry(version, data)) != null) {
                    throw new IOException("Duplicate profile data entry '" + id + "'");
                }
            }
            if (in.available() > 0) {
                throw new IOException("Trailing bytes after Atrium profile data");
            }
            return entries;
        }
    }
}
