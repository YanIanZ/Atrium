package dev.yanianz.atrium.module.kits;

import dev.yanianz.atrium.core.user.ComponentCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Binary form of {@link KitUsage} (cooldown timestamps and claim counts) for profile snapshots
 * (SourbyCraft MultiProfile): format byte, count, then per kit name, last claim time and claim count.
 */
final class KitUsageCodec implements ComponentCodec<KitUsage> {
    static final KitUsageCodec INSTANCE = new KitUsageCodec();
    private static final int FORMAT = 1;
    static final int MAX_KITS = 10_000;

    private KitUsageCodec() {}

    @Override
    public byte[] encode(KitUsage usage) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(16 + usage.claims().size() * 32);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(FORMAT);
            Map<String, KitUsage.Claim> sorted = new TreeMap<>(usage.claims());
            out.writeInt(sorted.size());
            for (Map.Entry<String, KitUsage.Claim> entry : sorted.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeLong(entry.getValue().lastMillis());
                out.writeInt(entry.getValue().count());
            }
        }
        return bytes.toByteArray();
    }

    @Override
    public KitUsage decode(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int format = in.readUnsignedByte();
            if (format != FORMAT) {
                throw new IOException("Unknown kit usage snapshot format " + format);
            }
            int count = in.readInt();
            if (count < 0 || count > MAX_KITS) {
                throw new IOException("Invalid kit count " + count);
            }
            Map<String, KitUsage.Claim> claims = new HashMap<>();
            for (int i = 0; i < count; i++) {
                String kit = in.readUTF();
                if (kit.isEmpty()) {
                    throw new IOException("Empty kit name in snapshot");
                }
                claims.put(kit, new KitUsage.Claim(in.readLong(), Math.max(0, in.readInt())));
            }
            if (in.available() > 0) {
                throw new IOException("Trailing bytes after the kit usage snapshot");
            }
            return new KitUsage(claims);
        }
    }
}
