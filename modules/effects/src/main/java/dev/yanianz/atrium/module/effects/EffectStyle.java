package dev.yanianz.atrium.module.effects;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.entity.EntityType;
import org.jspecify.annotations.Nullable;

/**
 * Immutable, validated effect definition. Parsing happens once on enable/reload; the event handlers only
 * read these records, never the raw config.
 */
public record EffectStyle(
        String key,
        boolean enabled,
        String permission,
        Duration cooldown,
        double radius,
        @Nullable ParticleSpec particles,
        @Nullable SoundSpec sound,
        @Nullable TitleSpec title,
        @Nullable String actionBar,
        @Nullable FireworkSpec firework,
        Set<EntityType> entityTypes) {

    public EffectStyle {
        entityTypes = Set.copyOf(entityTypes);
    }

    public boolean hasCooldown() {
        return !cooldown.isZero() && !cooldown.isNegative();
    }

    public boolean hasText() {
        return title != null || (actionBar != null && !actionBar.isBlank());
    }

    public boolean hasAnything() {
        return particles != null || sound != null || hasText() || firework != null;
    }

    /** Particle animation plus the data object built from the configured colour(s). */
    public record ParticleSpec(
            Shape shape,
            Particle particle,
            ParticleData.Check data,
            double radius,
            double height,
            int points,
            int count,
            double speed,
            int intervalTicks,
            int frames,
            double rotation) {}

    /**
     * A sound key: either a {@link org.bukkit.Sound} enum name or a namespaced key such as
     * {@code minecraft:entity.player.levelup}.
     */
    public record SoundSpec(String key, boolean enumName, float volume, float pitch, SoundCategory category) {}

    public record TitleSpec(String title, String subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        public boolean hasText() {
            return !title.isBlank() || !subtitle.isBlank();
        }
    }

    public record FireworkSpec(
            FireworkEffect.Type type,
            List<Color> colours,
            List<Color> fade,
            int power,
            int detonateAfterTicks,
            boolean flicker,
            boolean trail) {
        public FireworkSpec {
            colours = List.copyOf(colours);
            fade = List.copyOf(fade);
        }

        public FireworkEffect effect() {
            return FireworkEffect.builder()
                    .with(type)
                    .withColor(colours)
                    .withFade(fade)
                    .flicker(flicker)
                    .trail(trail)
                    .build();
        }
    }
}
