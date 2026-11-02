package dev.yanianz.atrium.module.homes;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.core.user.ComponentCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Binary form of {@link Homes} for profile snapshots (SourbyCraft MultiProfile): format byte, count, then
 * per home name, world, x, y, z, yaw, pitch and creation time. Decoding rejects unknown formats, absurd
 * counts and invalid names instead of guessing.
 */
final class HomesCodec implements ComponentCodec<Homes> {
    static final HomesCodec INSTANCE = new HomesCodec();
    private static final int FORMAT = 1;
    /** Far above any sane permission limit; protects the decoder from a corrupt count. */
    static final int MAX_HOMES = 10_000;

    private HomesCodec() {}

    @Override
    public byte[] encode(Homes homes) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(16 + homes.count() * 64);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(FORMAT);
            out.writeInt(homes.count());
            for (Home home : homes.list()) {
                StoredLocation l = home.location();
                out.writeUTF(home.name());
                out.writeUTF(l.world());
                out.writeDouble(l.x());
                out.writeDouble(l.y());
                out.writeDouble(l.z());
                out.writeFloat(l.yaw());
                out.writeFloat(l.pitch());
                out.writeLong(home.createdAt());
            }
        }
        return bytes.toByteArray();
    }

    @Override
    public Homes decode(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int format = in.readUnsignedByte();
            if (format != FORMAT) {
                throw new IOException("Unknown homes snapshot format " + format);
            }
            int count = in.readInt();
            if (count < 0 || count > MAX_HOMES) {
                throw new IOException("Invalid home count " + count);
            }
            List<Home> homes = new ArrayList<>(Math.min(count, 256));
            for (int i = 0; i < count; i++) {
                String name = in.readUTF();
                if (!Homes.validName(name)) {
                    throw new IOException("Invalid home name in snapshot");
                }
                String world = in.readUTF();
                StoredLocation location;
                try {
                    location = new StoredLocation(world, in.readDouble(), in.readDouble(), in.readDouble(),
                            in.readFloat(), in.readFloat());
                } catch (IllegalArgumentException e) {
                    throw new IOException("Invalid location of home " + name, e);
                }
                homes.add(new Home(name, location, in.readLong()));
            }
            if (in.available() > 0) {
                throw new IOException("Trailing bytes after the homes snapshot");
            }
            return Homes.of(homes);
        }
    }
}
