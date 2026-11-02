package dev.yanianz.atrium.module.inventories;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotCodecTest {
    /** Length-prefixed text that preserves empty slots; the framing under test is independent of Paper. */
    private static final SnapshotCodec.ItemCodec<String> CODEC = new SnapshotCodec.ItemCodec<>() {
        @Override
        public byte[] encode(List<String> items) {
            try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(items.size());
                for (String item : items) {
                    out.writeBoolean(item != null);
                    if (item != null) {
                        out.writeUTF(item);
                    }
                }
                out.flush();
                return bytes.toByteArray();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public List<String> decode(byte[] data) {
            try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(data))) {
                int size = in.readInt();
                List<String> items = new ArrayList<>(size);
                for (int index = 0; index < size; index++) {
                    items.add(in.readBoolean() ? in.readUTF() : null);
                }
                return items;
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
    };

    private static List<String> slots(int size, String value, int index) {
        List<String> items = new ArrayList<>(size);
        for (int slot = 0; slot < size; slot++) {
            items.add(slot == index ? value : null);
        }
        return items;
    }

    private static byte[] valid() throws Exception {
        return SnapshotCodec.encode(new SnapshotCodec.Contents<>(
                slots(SnapshotCodec.INVENTORY_SLOTS, "helm", 39),
                slots(SnapshotCodec.ENDER_SLOTS, "shulker", 26)), CODEC);
    }

    @Test
    void roundTripKeepsEverySlotOfBothSections() throws Exception {
        SnapshotCodec.Contents<String> contents = SnapshotCodec.decode(valid(), CODEC);
        assertEquals(SnapshotCodec.INVENTORY_SLOTS, contents.inventory().size());
        assertEquals(SnapshotCodec.ENDER_SLOTS, contents.enderChest().size());
        assertEquals("helm", contents.inventory().get(39));
        assertEquals("shulker", contents.enderChest().get(26));
        assertNull(contents.inventory().get(0));
        assertNull(contents.enderChest().get(0));
    }

    @Test
    void shortSectionsArePaddedWithEmptySlots() throws Exception {
        List<String> inventory = new ArrayList<>(List.of("only"));
        byte[] bytes = SnapshotCodec.encode(new SnapshotCodec.Contents<>(inventory, List.of()), CODEC);
        SnapshotCodec.Contents<String> contents = SnapshotCodec.decode(bytes, CODEC);
        assertEquals(SnapshotCodec.INVENTORY_SLOTS, contents.inventory().size());
        assertEquals("only", contents.inventory().get(0));
        assertNull(contents.inventory().get(40));
        assertEquals(SnapshotCodec.ENDER_SLOTS, contents.enderChest().size());
        assertTrue(contents.enderChest().stream().allMatch(java.util.Objects::isNull));
    }

    @Test
    void tooManySlotsAreRefusedInsteadOfTruncated() {
        List<String> overflow = slots(SnapshotCodec.INVENTORY_SLOTS + 1, "x", 41);
        assertThrows(SnapshotCodec.SnapshotFormatException.class,
                () -> SnapshotCodec.encode(new SnapshotCodec.Contents<>(overflow, List.of()), CODEC));
    }

    @Test
    void newerFormatIsRefusedWithItsVersion() throws Exception {
        byte[] raw = valid();
        raw[7] = (byte) (SnapshotCodec.FORMAT + 1);
        SnapshotCodec.UnsupportedFormatException error = assertThrows(SnapshotCodec.UnsupportedFormatException.class,
                () -> SnapshotCodec.decode(raw, CODEC));
        assertEquals(SnapshotCodec.FORMAT + 1, error.format());
    }

    @Test
    void badSignatureIsRefused() throws Exception {
        byte[] raw = valid();
        raw[0] = 0;
        assertThrows(SnapshotCodec.SnapshotFormatException.class, () -> SnapshotCodec.decode(raw, CODEC));
    }

    @Test
    void truncatedAndTrailingDataAreRefused() throws Exception {
        byte[] raw = valid();
        assertThrows(SnapshotCodec.SnapshotFormatException.class,
                () -> SnapshotCodec.decode(Arrays.copyOf(raw, raw.length - 5), CODEC));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.write(raw);
            out.writeInt(1);
        }
        assertThrows(SnapshotCodec.SnapshotFormatException.class,
                () -> SnapshotCodec.decode(bytes.toByteArray(), CODEC));
    }

    @Test
    void missingDataIsRefused() {
        assertThrows(SnapshotCodec.SnapshotFormatException.class, () -> SnapshotCodec.decode(null, CODEC));
        assertThrows(SnapshotCodec.SnapshotFormatException.class, () -> SnapshotCodec.decode(new byte[0], CODEC));
    }
}
