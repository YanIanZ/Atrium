package dev.yanianz.atrium.module.particles;

import java.util.Locale;
import org.bukkit.Particle;
import org.jspecify.annotations.Nullable;

/** Helpers for translating particle names between commands, permissions and the Bukkit enum. */
final class ParticleNames {
    private ParticleNames() {}

    /** Lower-case key used in commands and permissions, e.g. {@code dust_color_transition}. */
    static String key(Particle particle) {
        return particle.getKey().getKey().toLowerCase(Locale.ROOT);
    }

    static String permission(Particle particle) {
        return "atrium.particles.particle." + key(particle);
    }

    /** Resolves a name from a command or config; accepts {@code dust}, {@code DUST} and {@code dust-color}. */
    static @Nullable Particle resolve(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (Particle particle : Particle.values()) {
            if (particle.getKey().getKey().equals(normalized)) {
                return particle;
            }
        }
        try {
            return Particle.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
