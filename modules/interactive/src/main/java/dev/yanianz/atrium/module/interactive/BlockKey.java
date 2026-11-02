package dev.yanianz.atrium.module.interactive;

import java.util.Objects;
import java.util.Optional;
import org.bukkit.block.Block;

/** A block position by world name. Text form {@code world;x;y;z} (world taken from the front, numbers from the end). */
record BlockKey(String world, int x, int y, int z) {
    BlockKey {
        Objects.requireNonNull(world, "world");
    }

    static BlockKey of(Block block) {
        return new BlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    String encode() {
        return world + ';' + x + ';' + y + ';' + z;
    }

    static Optional<BlockKey> decode(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String[] parts = text.split(";", -1);
        if (parts.length < 4) {
            return Optional.empty();
        }
        int n = parts.length;
        String world = String.join(";", java.util.Arrays.copyOf(parts, n - 3));
        try {
            return world.isEmpty() ? Optional.empty() : Optional.of(new BlockKey(world, Integer.parseInt(parts[n - 3]),
                    Integer.parseInt(parts[n - 2]), Integer.parseInt(parts[n - 1])));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return world + " " + x + " " + y + " " + z;
    }
}
