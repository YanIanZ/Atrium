package dev.yanianz.atrium.module.staff;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Binary format of a {@link StaffSnapshot}. Pure code: no Bukkit access, so it is unit-tested directly.
 * Every length is bounded, so a corrupt row can never allocate an unbounded buffer.
 */
final class StaffSnapshotCodec {
    private static final int MAGIC = 0x53544146; // "STAF"
    private static final int MAX_BLOB = 8 * 1024 * 1024;
    private static final int VANISH_UNKNOWN = 2;

    private StaffSnapshotCodec() {
    }

    static byte[] encode(StaffSnapshot snapshot) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(MAGIC);
                out.writeInt(snapshot.format());
                out.writeUTF(snapshot.world());
                out.writeDouble(snapshot.x());
                out.writeDouble(snapshot.y());
                out.writeDouble(snapshot.z());
                out.writeFloat(snapshot.yaw());
                out.writeFloat(snapshot.pitch());
                out.writeUTF(snapshot.gameMode());
                out.writeInt(snapshot.level());
                out.writeFloat(snapshot.exp());
                out.writeInt(snapshot.totalExperience());
                out.writeBoolean(snapshot.allowFlight());
                out.writeBoolean(snapshot.flying());
                if (snapshot.format() >= 3) {
                    out.writeInt(checkSlots("storage", snapshot.storageSlots(), StaffSnapshot.MAX_STORAGE_SLOTS));
                    out.writeInt(checkSlots("armour", snapshot.armorSlots(), StaffSnapshot.MAX_ARMOR_SLOTS));
                    out.writeInt(checkSlots("off hand", snapshot.offhandSlots(), StaffSnapshot.MAX_OFFHAND_SLOTS));
                }
                writeBlob(out, snapshot.storage());
                writeBlob(out, snapshot.armor());
                writeBlob(out, snapshot.offhand());
                writeBlob(out, snapshot.effects());
                if (snapshot.format() >= 2) {
                    Boolean vanished = snapshot.vanished();
                    out.writeByte(vanished == null ? VANISH_UNKNOWN : vanished ? 1 : 0);
                }
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Encoding a staff snapshot failed", e);
        }
    }

    static StaffSnapshot decode(byte[] data) {
        if (data == null || data.length < Integer.BYTES * 2) {
            throw new IllegalArgumentException("Staff snapshot is empty or truncated");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readInt() != MAGIC) {
                throw new IllegalArgumentException("Not a staff snapshot");
            }
            int format = in.readInt();
            if (format < 1 || format > StaffSnapshot.CURRENT_FORMAT) {
                throw new IllegalArgumentException("Unsupported staff snapshot format " + format);
            }
            String world = in.readUTF();
            double x = in.readDouble();
            double y = in.readDouble();
            double z = in.readDouble();
            float yaw = in.readFloat();
            float pitch = in.readFloat();
            String gameMode = in.readUTF();
            int level = in.readInt();
            float exp = in.readFloat();
            int totalExperience = in.readInt();
            boolean allowFlight = in.readBoolean();
            boolean flying = in.readBoolean();
            int storageSlots = StaffSnapshot.UNKNOWN_SLOTS;
            int armorSlots = StaffSnapshot.UNKNOWN_SLOTS;
            int offhandSlots = StaffSnapshot.UNKNOWN_SLOTS;
            if (format >= 3) {
                storageSlots = checkSlots("storage", in.readInt(), StaffSnapshot.MAX_STORAGE_SLOTS);
                armorSlots = checkSlots("armour", in.readInt(), StaffSnapshot.MAX_ARMOR_SLOTS);
                offhandSlots = checkSlots("off hand", in.readInt(), StaffSnapshot.MAX_OFFHAND_SLOTS);
            }
            byte[] storage = readBlob(in);
            byte[] armor = readBlob(in);
            byte[] offhand = readBlob(in);
            byte[] effects = readBlob(in);
            Boolean vanished = null;
            if (format >= 2) {
                int flag = in.readUnsignedByte();
                vanished = switch (flag) {
                    case 0 -> Boolean.FALSE;
                    case 1 -> Boolean.TRUE;
                    case VANISH_UNKNOWN -> null;
                    default -> throw new IllegalArgumentException("Invalid vanish flag " + flag);
                };
            }
            if (in.available() != 0) {
                throw new IllegalArgumentException("Staff snapshot has trailing data");
            }
            return new StaffSnapshot(format, world, x, y, z, yaw, pitch, gameMode, level, exp,
                    totalExperience, allowFlight, flying, storage, armor, offhand, effects, vanished, storageSlots,
                    armorSlots, offhandSlots);
        } catch (IOException e) {
            throw new IllegalArgumentException("Staff snapshot is corrupt", e);
        }
    }

    /** A recorded slot count must be known and within its section's bound. */
    static int checkSlots(String section, int slots, int max) {
        if (slots < 0 || slots > max) {
            throw new IllegalArgumentException("Staff snapshot " + section + " has " + slots
                    + " slots (supported: 0-" + max + ")");
        }
        return slots;
    }

    private static void writeBlob(DataOutputStream out, byte[] blob) throws IOException {
        out.writeInt(blob.length);
        out.write(blob);
    }

    private static byte[] readBlob(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_BLOB || length > in.available()) {
            throw new IllegalArgumentException("Invalid staff snapshot blob length " + length);
        }
        byte[] blob = new byte[length];
        in.readFully(blob);
        return blob;
    }
}
