package dev.yanianz.atrium.module.inventories;

/**
 * Pure framing of a stored snapshot: a magic number, a format version and two length-prefixed item
 * sections (inventory with armour and offhand, then ender chest).
 *
 * <p>The item encoding itself is supplied by the caller ({@link ItemCodec}), so the framing can be tested
 * without a running server. A newer format is refused with {@link UnsupportedFormatException} instead of
 * being parsed with the wrong layout.
 */
final class SnapshotCodec {
    /** Layout written by this build. Bump it when the meaning or order of the sections changes. */
    static final int FORMAT = 1;
    /** {@code ATRS}: Atrium snapshot. */
    static final int MAGIC = 0x41545253;
    /** 36 storage slots + 4 armour slots + offhand. */
    static final int INVENTORY_SLOTS = 41;
    /** A single ender chest page. */
    static final int ENDER_SLOTS = 27;
    /** Refuse absurd lengths from a corrupt row before allocating. */
    private static final int MAX_SECTION_BYTES = 16 * 1024 * 1024;

    interface ItemCodec<T> {
        byte[] encode(java.util.List<T> items);

        java.util.List<T> decode(byte[] data) throws SnapshotFormatException;
    }

    record Contents<T>(java.util.List<T> inventory, java.util.List<T> enderChest) {}

    static class SnapshotFormatException extends Exception {
        private static final long serialVersionUID = 1L;

        SnapshotFormatException(String message) {
            super(message);
        }

        SnapshotFormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static final class UnsupportedFormatException extends SnapshotFormatException {
        private static final long serialVersionUID = 1L;
        private final int format;

        UnsupportedFormatException(int format) {
            super("snapshot format " + format + " is newer than supported format " + FORMAT);
            this.format = format;
        }

        int format() {
            return format;
        }
    }

    private SnapshotCodec() {}

    static <T> byte[] encode(Contents<T> contents, ItemCodec<T> codec) throws SnapshotFormatException {
        try (java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                java.io.DataOutputStream out = new java.io.DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT);
            writeSection(out, contents.inventory(), INVENTORY_SLOTS, codec);
            writeSection(out, contents.enderChest(), ENDER_SLOTS, codec);
            out.flush();
            return bytes.toByteArray();
        } catch (java.io.IOException e) {
            throw new SnapshotFormatException("cannot encode snapshot: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new SnapshotFormatException("cannot serialize snapshot items: " + e.getMessage(), e);
        }
    }

    static <T> Contents<T> decode(byte[] raw, ItemCodec<T> codec) throws SnapshotFormatException {
        if (raw == null || raw.length < 24) {
            throw new SnapshotFormatException("snapshot data is missing or truncated");
        }
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(raw))) {
            int magic = in.readInt();
            if (magic != MAGIC) {
                throw new SnapshotFormatException("unknown snapshot signature");
            }
            int format = in.readInt();
            if (format > FORMAT) {
                throw new UnsupportedFormatException(format);
            }
            if (format < 1) {
                throw new SnapshotFormatException("unknown snapshot format " + format);
            }
            java.util.List<T> inventory = readSection(in, INVENTORY_SLOTS, codec);
            java.util.List<T> ender = readSection(in, ENDER_SLOTS, codec);
            if (in.available() > 0) {
                throw new SnapshotFormatException("unexpected trailing snapshot data");
            }
            return new Contents<>(inventory, ender);
        } catch (java.io.EOFException e) {
            throw new SnapshotFormatException("snapshot data is truncated", e);
        } catch (java.io.IOException e) {
            throw new SnapshotFormatException("cannot read snapshot: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new SnapshotFormatException("cannot deserialize snapshot items: " + e.getMessage(), e);
        }
    }

    private static <T> void writeSection(java.io.DataOutputStream out, java.util.List<T> items, int slots,
            ItemCodec<T> codec) throws java.io.IOException, SnapshotFormatException {
        java.util.List<T> fitted = fit(items, slots);
        byte[] encoded = codec.encode(fitted);
        out.writeInt(slots);
        out.writeInt(encoded.length);
        out.write(encoded);
    }

    private static <T> java.util.List<T> readSection(java.io.DataInputStream in, int expected, ItemCodec<T> codec)
            throws java.io.IOException, SnapshotFormatException {
        int slots = in.readInt();
        long length = Integer.toUnsignedLong(in.readInt());
        if (slots != expected) {
            throw new SnapshotFormatException("unexpected slot count " + slots + " (expected " + expected + ")");
        }
        if (length < 0 || length > MAX_SECTION_BYTES) {
            throw new SnapshotFormatException("invalid item section length " + length);
        }
        byte[] data = new byte[(int) length];
        in.readFully(data);
        return fit(codec.decode(data), expected);
    }

    /** Exactly {@code slots} entries, padding missing slots with {@code null} and refusing extra ones. */
    static <T> java.util.List<T> fit(java.util.List<T> items, int slots) throws SnapshotFormatException {
        if (items == null) {
            throw new SnapshotFormatException("missing item section");
        }
        if (items.size() > slots) {
            throw new SnapshotFormatException("item section has " + items.size() + " slots (expected at most " + slots + ")");
        }
        java.util.List<T> result = new java.util.ArrayList<>(slots);
        for (int i = 0; i < slots; i++) {
            result.add(i < items.size() ? items.get(i) : null);
        }
        return java.util.Collections.unmodifiableList(result);
    }
}
