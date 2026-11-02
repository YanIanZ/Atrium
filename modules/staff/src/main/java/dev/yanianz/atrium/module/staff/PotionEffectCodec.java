package dev.yanianz.atrium.module.staff;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Encodes active potion effects without Bukkit's deprecated serialization API. */
final class PotionEffectCodec {
    private static final int MAX_EFFECTS = 128;

    private PotionEffectCodec() {
    }

    static byte[] encode(Collection<PotionEffect> effects) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                List<PotionEffect> list = List.copyOf(effects);
                out.writeInt(list.size());
                for (PotionEffect effect : list) {
                    out.writeUTF(effect.getType().getKey().toString());
                    out.writeInt(effect.getDuration());
                    out.writeInt(effect.getAmplifier());
                    out.writeBoolean(effect.isAmbient());
                    out.writeBoolean(effect.hasParticles());
                    out.writeBoolean(effect.hasIcon());
                }
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Encoding potion effects failed", e);
        }
    }

    static List<PotionEffect> decode(byte[] data, java.util.function.Consumer<String> warn) {
        if (data == null || data.length == 0) {
            return List.of();
        }
        List<PotionEffect> effects = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int count = in.readInt();
            if (count < 0 || count > MAX_EFFECTS) {
                warn.accept("Ignoring a staff snapshot with " + count + " potion effects");
                return List.of();
            }
            for (int i = 0; i < count; i++) {
                String key = in.readUTF();
                int duration = in.readInt();
                int amplifier = in.readInt();
                boolean ambient = in.readBoolean();
                boolean particles = in.readBoolean();
                boolean icon = in.readBoolean();
                NamespacedKey namespaced = NamespacedKey.fromString(key);
                PotionEffectType type = namespaced == null ? null : Registry.EFFECT.get(namespaced);
                if (type == null) {
                    warn.accept("Dropping unknown potion effect " + key + " from a staff snapshot");
                    continue;
                }
                effects.add(new PotionEffect(type, duration, amplifier, ambient, particles, icon));
            }
        } catch (IOException | RuntimeException e) {
            warn.accept("Ignoring corrupt potion effects in a staff snapshot: " + e.getMessage());
            return List.of();
        }
        return List.copyOf(effects);
    }
}
