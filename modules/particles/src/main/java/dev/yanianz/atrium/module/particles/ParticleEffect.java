package dev.yanianz.atrium.module.particles;

import dev.yanianz.atrium.module.particles.shape.Style;
import java.util.Objects;
import org.bukkit.Particle;

/**
 * One active cosmetic: a shape plus the particle and colours drawn on it.
 */
public record ParticleEffect(Style style, Particle particle, ParticleColours colours, double radius, double density,
        double speed) {
    public ParticleEffect {
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(particle, "particle");
        Objects.requireNonNull(colours, "colours");
        radius = Math.max(0.05, Math.min(8.0, radius));
        density = Math.max(0.05, Math.min(8.0, density));
        speed = Math.max(0.0, Math.min(360.0, speed));
    }

    public ParticleEffect withParticle(Particle value) {
        return new ParticleEffect(style, value, colours, radius, density, speed);
    }

    public ParticleEffect withColours(ParticleColours value) {
        return new ParticleEffect(style, particle, value, radius, density, speed);
    }

    /** Compact description used in list messages and menu lore. */
    public String describe() {
        return style.key() + ":" + ParticleNames.key(particle) + ":" + ParticleColours.hex(colours.primary());
    }
}
