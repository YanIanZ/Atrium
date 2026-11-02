package dev.yanianz.atrium.module.kits;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jspecify.annotations.Nullable;

/**
 * Kit slot layout and its binary form. Each slot is stored as {@code ItemStack#serializeAsBytes} (full data
 * components, upgraded by the server's data fixers on load) behind a small versioned header:
 * {@code magic, version, slotCount, (length, bytes)*} where length 0 means an empty slot.
 */
public final class KitItems {
    public static final int SIZE = 41;
    public static final int ARMOR_START = 36;
    public static final int OFFHAND = 40;
    private static final int MAGIC = 0x41544B54; // "ATKT"
    private static final int VERSION = 1;
    private static final int MAX_SLOT_BYTES = 8 * 1024 * 1024;

    private KitItems() {}

    /** Encodes raw per-slot data (null or empty = empty slot). Pure; used by tests. */
    public static byte[] encode(List<byte @Nullable []> slots) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(slots.size());
            for (byte[] slot : slots) {
                if (slot == null || slot.length == 0) {
                    out.writeInt(0);
                } else {
                    out.writeInt(slot.length);
                    out.write(slot);
                }
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Decodes per-slot data; empty slots are {@code null}. Rejects malformed input. */
    public static List<byte @Nullable []> decode(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readInt() != MAGIC) throw new IllegalArgumentException("not a kit item blob");
            int version = in.readInt();
            if (version != VERSION) throw new IllegalArgumentException("unsupported kit item version " + version);
            int count = in.readInt();
            if (count < 0 || count > 256) throw new IllegalArgumentException("bad slot count " + count);
            List<byte[]> slots = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int length = in.readInt();
                if (length < 0 || length > MAX_SLOT_BYTES) throw new IllegalArgumentException("bad slot length " + length);
                if (length == 0) {
                    slots.add(null);
                } else {
                    byte[] slot = new byte[length];
                    in.readFully(slot);
                    slots.add(slot);
                }
            }
            if (in.available() > 0) throw new IllegalArgumentException("trailing bytes in kit item blob");
            return slots;
        } catch (IOException e) {
            throw new IllegalArgumentException("truncated kit item blob", e);
        }
    }

    /** Captures storage, armor and offhand of a player. Call on the player's thread. */
    static ItemStack[] capture(PlayerInventory inventory) {
        ItemStack[] slots = new ItemStack[SIZE];
        ItemStack[] storage = inventory.getStorageContents();
        for (int i = 0; i < Math.min(36, storage.length); i++) slots[i] = copy(storage[i]);
        ItemStack[] armor = inventory.getArmorContents();
        for (int i = 0; i < Math.min(4, armor.length); i++) slots[ARMOR_START + i] = copy(armor[i]);
        slots[OFFHAND] = copy(inventory.getItemInOffHand());
        return slots;
    }

    private static @Nullable ItemStack copy(@Nullable ItemStack item) {
        return item == null || item.isEmpty() ? null : item.clone();
    }

    static boolean isEmpty(ItemStack[] slots) {
        for (ItemStack item : slots) if (item != null && !item.isEmpty()) return false;
        return true;
    }

    /** Serializes a single item (the kit icon); empty becomes {@code null}. */
    static byte @Nullable [] serializeIcon(@Nullable ItemStack icon) {
        return icon == null || icon.isEmpty() ? null : icon.serializeAsBytes();
    }

    /** Reads a stored icon; malformed data is rejected so a broken row fails loudly instead of silently. */
    static @Nullable ItemStack deserializeIcon(byte @Nullable [] data) {
        return data == null || data.length == 0 ? null : ItemStack.deserializeBytes(data);
    }

    static byte[] serialize(ItemStack[] slots) {
        List<byte[]> raw = new ArrayList<>(slots.length);
        for (ItemStack item : slots) raw.add(item == null || item.isEmpty() ? null : item.serializeAsBytes());
        return encode(raw);
    }

    static ItemStack[] deserialize(byte[] data) {
        List<byte[]> raw = decode(data);
        ItemStack[] slots = new ItemStack[SIZE];
        for (int i = 0; i < Math.min(SIZE, raw.size()); i++) {
            byte[] slot = raw.get(i);
            slots[i] = slot == null ? null : ItemStack.deserializeBytes(slot);
        }
        return slots;
    }
}
