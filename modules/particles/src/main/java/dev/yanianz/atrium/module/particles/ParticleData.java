package dev.yanianz.atrium.module.particles;

import com.destroystokyo.paper.ParticleBuilder;
import java.util.Locale;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.slf4j.Logger;

/**
 * Builds the data object a particle needs (dust colour, item, block, ...). Particles whose data type is
 * not supported are filtered out of the menu and refused by commands instead of throwing at spawn time.
 */
final class ParticleData {
    private final ItemStack item;
    private final BlockData block;

    ParticleData(ParticlesConfig config, Logger logger) {
        this.item = new ItemStack(material(config.itemParticleMaterial, Material.DIAMOND, logger));
        Material blockMaterial = material(config.blockParticleMaterial, Material.STONE, logger);
        BlockData resolved;
        try {
            resolved = blockMaterial.createBlockData();
        } catch (RuntimeException e) {
            logger.warn("Block particle material '{}' cannot be used as a block; using STONE", blockMaterial, e);
            resolved = Material.STONE.createBlockData();
        }
        this.block = resolved;
    }

    private static Material material(String name, Material fallback, Logger logger) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        Material material = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
        if (material == null) {
            logger.warn("Unknown particle material '{}'; using {}", name, fallback);
            return fallback;
        }
        return material;
    }

    static boolean supported(Particle particle) {
        Class<?> type = particle.getDataType();
        return type == Void.class
                || type == Particle.DustOptions.class
                || type == Particle.DustTransition.class
                || type == Color.class
                || type == ItemStack.class
                || type == BlockData.class
                || type == Integer.class
                || type == Float.class;
    }

    void apply(ParticleBuilder builder, Particle particle, Color primary, Color secondary, float size) {
        Class<?> type = particle.getDataType();
        if (type == Particle.DustOptions.class) {
            builder.data(new Particle.DustOptions(primary, size));
        } else if (type == Particle.DustTransition.class) {
            builder.data(new Particle.DustTransition(primary, secondary, size));
        } else if (type == Color.class) {
            builder.data(primary);
        } else if (type == ItemStack.class) {
            builder.data(item);
        } else if (type == BlockData.class) {
            builder.data(block);
        } else if (type == Integer.class) {
            builder.data(0);
        } else if (type == Float.class) {
            builder.data(0.0f);
        }
    }
}
