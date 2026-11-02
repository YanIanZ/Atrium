package dev.yanianz.atrium.module.inventories;

/**
 * Pure framing of a stored snapshot: a magic number, a format version and two length-prefixed item
 * sections (inventory with armour and offhand, then ender chest).
 *
 * <p>The inventory section is {@code storage + 4 armour + 1 offhand}; the storage part is everything before the
 * last five slots. Format 1 always stored exactly {@value #INVENTORY_SLOTS} and {@value #ENDER_SLOTS} slots.
 * Format 2 stores the real section sizes of the server that captured it (forks and plugins resize ender chests
 * up to six rows), bounded by {@link #MAX_INVENTORY_SLOTS} and {@link #MAX_ENDER_SLOTS}; shorter sections are
 * padded to the vanilla sizes so every reader sees at least the classic layout.
 *
 * <p>The item encoding itself is supplied by the caller ({@link ItemCodec}), so the framing can be tested
 * without a running server. A newer format is refused with {@link UnsupportedFormatException} instead of
 * being parsed with the wrong layout.
 */
final class SnapshotCodec {
    /** Layout written by this build. Bump it when the meaning or order of the sections changes. */
    static final int FORMAT = 2;
    /** The first format: fixed 41 + 27 slots. Still decoded. */
    static final int LEGACY_FORMAT = 1;
    /** {@code ATRS}: Atrium snapshot. */
    static final int MAGIC = 0x41545253;
    /** Armour slots after the storage part: boots, leggings, chestplate, helmet. */
    static final int ARMOUR_SLOTS = 4;
    /** Armour and offhand: the tail of the inventory section. */
    static final int EQUIPMENT_SLOTS = ARMOUR_SLOTS + 1;
    /** Vanilla storage: hotbar and main inventory. */
    static final int VANILLA_STORAGE_SLOTS = 36;
    /** 36 storage slots + 4 armour slots + offhand: the vanilla (and minimum) inventory section. */
    static final int INVENTORY_SLOTS = VANILLA_STORAGE_SLOTS + EQUIPMENT_SLOTS;
    /** A vanilla ender chest page: the minimum stored ender section. */
    static final int ENDER_SLOTS = 27;
    /** Largest storage part accepted from a fork (six rows). */
    static final int MAX_STORAGE_SLOTS = 54;
    /** Hard cap of the inventory section. */
    static final int MAX_INVENTORY_SLOTS = MAX_STORAGE_SLOTS + EQUIPMENT_SLOTS;
    /** A six-row ender chest: the largest chest inventory Paper can create. */
    static final int MAX_ENDER_SLOTS = 54;
    /** Refuse absurd lengths from a corrupt row before allocating. */
    private static final int MAX_SECTION_BYTES = 16 * 1024 * 1024;

    interface ItemCodec<T> {
        byte[] encode(java.util.List<T> items);

        java.util.List<T> decode(byte[] data) throws SnapshotFormatException;
    }

    /**
     * Decoded sections. {@code inventory} is storage, then 4 armour slots, then the offhand; use
     * {@link #storage()}, {@link #armour()} and {@link #offhand()} instead of fixed indices.
     */
    record Contents<T>(java.util.List<T> inventory, java.util.List<T> enderChest) {
        int storageSlots() {
            return Math.max(0, inventory.size() - EQUIPMENT_SLOTS);
        }

        java.util.List<T> storage() {
            return inventory.subList(0, storageSlots());
        }

        java.util.List<T> armour() {
            int from = storageSlots();
            return inventory.subList(from, Math.min(inventory.size(), from + ARMOUR_SLOTS));
        }

        java.util.List<T> offhand() {
            int from = Math.min(inventory.size(), storageSlots() + ARMOUR_SLOTS);
            return inventory.subList(from, inventory.size());
        }
    }

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
            writeSection(out, contents.inventory(), inventorySlots(contents.inventory()), codec);
            writeSection(out, contents.enderChest(), enderSlots(contents.enderChest()), codec);
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
            java.util.List<T> inventory;
            java.util.List<T> ender;
            if (format == LEGACY_FORMAT) {
                inventory = readSection(in, INVENTORY_SLOTS, INVENTORY_SLOTS, codec);
                ender = readSection(in, ENDER_SLOTS, ENDER_SLOTS, codec);
            } else {
                inventory = readSection(in, INVENTORY_SLOTS, MAX_INVENTORY_SLOTS, codec);
                ender = readSection(in, ENDER_SLOTS, MAX_ENDER_SLOTS, codec);
            }
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

    private static <T> java.util.List<T> readSection(java.io.DataInputStream in, int min, int max,
            ItemCodec<T> codec) throws java.io.IOException, SnapshotFormatException {
        int slots = in.readInt();
        long length = Integer.toUnsignedLong(in.readInt());
        if (slots < min || slots > max) {
            throw new SnapshotFormatException(min == max
                    ? "unexpected slot count " + slots + " (expected " + min + ")"
                    : "unexpected slot count " + slots + " (expected " + min + "-" + max + ")");
        }
        if (length < 0 || length > MAX_SECTION_BYTES) {
            throw new SnapshotFormatException("invalid item section length " + length);
        }
        byte[] data = new byte[(int) length];
        in.readFully(data);
        return fit(codec.decode(data), slots);
    }

    /** Stored size of an inventory section: at least the vanilla 41, at most {@link #MAX_INVENTORY_SLOTS}. */
    static int inventorySlots(java.util.List<?> items) throws SnapshotFormatException {
        return bounded(items, INVENTORY_SLOTS, MAX_INVENTORY_SLOTS, "inventory");
    }

    /** Stored size of an ender section: at least the vanilla 27, at most {@link #MAX_ENDER_SLOTS}. */
    static int enderSlots(java.util.List<?> items) throws SnapshotFormatException {
        return bounded(items, ENDER_SLOTS, MAX_ENDER_SLOTS, "ender chest");
    }

    private static int bounded(java.util.List<?> items, int min, int max, String section)
            throws SnapshotFormatException {
        if (items == null) {
            throw new SnapshotFormatException("missing " + section + " section");
        }
        if (items.size() > max) {
            throw new SnapshotFormatException(section + " section has " + items.size()
                    + " slots (at most " + max + " are supported)");
        }
        return Math.max(min, items.size());
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
