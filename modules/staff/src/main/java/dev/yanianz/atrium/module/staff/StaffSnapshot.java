package dev.yanianz.atrium.module.staff;

import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * Everything needed to put a player back exactly as they were before staff mode.
 *
 * <p>Item and effect payloads stay opaque byte arrays so the snapshot can be encoded, stored and
 * decoded without a running server; {@link StaffSnapshots} fills and applies them.
 */
public record StaffSnapshot(
        int format,
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        String gameMode,
        int level,
        float exp,
        int totalExperience,
        boolean allowFlight,
        boolean flying,
        byte[] storage,
        byte[] armor,
        byte[] offhand,
        byte[] effects,
        @Nullable Boolean vanished) {

    /**
     * Bumped whenever the encoded layout changes; older rows are read on a best-effort basis.
     * 2: adds the vanish state before staff mode ({@code null} = unknown, rows of format 1).
     */
    public static final int CURRENT_FORMAT = 2;

    public StaffSnapshot {
        world = world == null ? "" : world;
        gameMode = gameMode == null ? "SURVIVAL" : gameMode;
        storage = storage == null ? new byte[0] : storage.clone();
        armor = armor == null ? new byte[0] : armor.clone();
        offhand = offhand == null ? new byte[0] : offhand.clone();
        effects = effects == null ? new byte[0] : effects.clone();
    }

    @Override
    public byte[] storage() {
        return storage.clone();
    }

    @Override
    public byte[] armor() {
        return armor.clone();
    }

    @Override
    public byte[] offhand() {
        return offhand.clone();
    }

    @Override
    public byte[] effects() {
        return effects.clone();
    }

    /**
     * Whether serialized storage, armour and off hand equal this snapshot byte for byte. Used to detect
     * a restore that already reached the inventory before a crash.
     */
    public boolean itemsEqual(byte[] otherStorage, byte[] otherArmor, byte[] otherOffhand) {
        return Arrays.equals(storage, otherStorage)
                && Arrays.equals(armor, otherArmor)
                && Arrays.equals(offhand, otherOffhand);
    }

    public boolean sameItems(StaffSnapshot other) {
        return Arrays.equals(storage, other.storage)
                && Arrays.equals(armor, other.armor)
                && Arrays.equals(offhand, other.offhand)
                && Arrays.equals(effects, other.effects);
    }
}
