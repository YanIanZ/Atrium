package dev.yanianz.atrium.module.effects;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.entity.EntityType;
import org.jspecify.annotations.Nullable;

/**
 * Turns the raw config into {@link EffectStyle} records. Entries are parsed one by one and a broken piece
 * (unknown particle, bad colour, unknown sound) only drops that piece; the other parts of the same effect
 * and every other event still work. Every problem is returned as a warning for the console.
 */
public final class EffectParser {
    private static final Pattern NAMESPACED_KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern PERMISSION = Pattern.compile("[A-Za-z0-9_.*:-]{1,128}");

    private EffectParser() {}

    public record Result(Map<String, EffectStyle> effects, List<String> warnings) {
        public Result {
            effects = Map.copyOf(effects);
            warnings = List.copyOf(warnings);
        }
    }

    public static Result parse(Map<String, EffectsConfig.EventEffect> definitions) {
        Map<String, EffectStyle> effects = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        if (definitions != null) {
            for (Map.Entry<String, EffectsConfig.EventEffect> entry : definitions.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                    continue;
                }
                String key = EffectKeys.normalize(entry.getKey());
                if (!EffectKeys.isKnown(key)) {
                    warnings.add("Unknown event key '" + entry.getKey() + "'; known keys: "
                            + String.join(", ", EffectKeys.ALL));
                    continue;
                }
                List<String> local = new ArrayList<>();
                EffectStyle style = parseOne(key, entry.getValue(), local);
                // Warnings for a disabled entry are noise; the admin asked for it to stay off.
                if (entry.getValue().enabled) {
                    warnings.addAll(local);
                }
                effects.put(key, style);
            }
        }
        return new Result(effects, warnings);
    }

    private static EffectStyle parseOne(String key, EffectsConfig.EventEffect raw, List<String> warnings) {
        String permission = "";
        if (raw.permission != null && !raw.permission.isBlank()) {
            String candidate = raw.permission.strip();
            if (PERMISSION.matcher(candidate).matches()) {
                permission = candidate;
            } else {
                warnings.add(prefix(key) + "invalid permission '" + candidate + "'; the effect is open to everyone");
            }
        }

        long cooldownSeconds = Math.max(0, Math.min(86_400, raw.cooldownSeconds));
        double viewRadius = Math.max(4.0, Math.min(64.0, raw.viewRadius));

        Set<EntityType> entityTypes = new LinkedHashSet<>();
        if (raw.entityTypes != null) {
            for (String name : raw.entityTypes) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                try {
                    entityTypes.add(EntityType.valueOf(name.strip().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    warnings.add(prefix(key) + "unknown entity type '" + name + "' ignored");
                }
            }
        }

        EffectStyle.ParticleSpec particles = parseParticles(key, raw.particle, warnings);
        EffectStyle.SoundSpec sound = parseSound(key, raw.sound, warnings);
        EffectStyle.TitleSpec title = parseTitle(key, raw.title, warnings);
        String actionBar = raw.actionBar == null || raw.actionBar.isBlank() ? null : raw.actionBar;
        EffectStyle.FireworkSpec firework = parseFirework(key, raw.firework, warnings);

        EffectStyle style = new EffectStyle(key, raw.enabled, permission, Duration.ofSeconds(cooldownSeconds),
                viewRadius, particles, sound, title, actionBar, firework, entityTypes);
        if (raw.enabled && !style.hasAnything()) {
            warnings.add(prefix(key) + "enabled but has no particle, sound, title, action bar or firework");
        }
        return style;
    }

    private static EffectStyle.@Nullable ParticleSpec parseParticles(
            String key, EffectsConfig.Particles raw, List<String> warnings) {
        if (raw == null || raw.particle == null || raw.particle.isBlank()) {
            return null;
        }
        Particle particle = ParticleData.particle(raw.particle);
        if (particle == null) {
            warnings.add(prefix(key) + "unknown particle '" + raw.particle.strip() + "'");
            return null;
        }
        if (!ParticleData.supported(particle)) {
            warnings.add(prefix(key) + "particle " + particle.name() + " needs "
                    + particle.getDataType().getSimpleName() + " data, which this module does not support");
            return null;
        }
        Shape shape = Shape.parse(raw.shape);
        if (shape == null) {
            warnings.add(prefix(key) + "unknown shape '" + raw.shape + "'; use one of " + String.join(", ", Shape.ids()));
            return null;
        }
        Color primary = null;
        if (raw.colour != null && !raw.colour.isBlank()) {
            primary = ParticleData.colour(raw.colour).orElse(null);
            if (primary == null) {
                warnings.add(prefix(key) + "invalid colour '" + raw.colour + "'; use #RRGGBB");
            }
        }
        Color secondary = null;
        if (raw.colour2 != null && !raw.colour2.isBlank()) {
            secondary = ParticleData.colour(raw.colour2).orElse(null);
            if (secondary == null) {
                warnings.add(prefix(key) + "invalid fade colour '" + raw.colour2 + "'; use #RRGGBB");
            }
        }
        ParticleData.Check check = ParticleData.check(particle, primary, secondary, raw.size);
        if (!check.valid()) {
            warnings.add(prefix(key) + check.error());
            return null;
        }
        int points = Shapes.clamp(raw.points, Shapes.MIN_POINTS, Shapes.MAX_POINTS);
        int count = Math.max(1, Math.min(8, raw.count));
        int interval = Math.max(1, Math.min(40, raw.intervalTicks));
        int frames = Math.max(1, Math.min(400, raw.frames));
        double radius = Math.max(Shapes.MIN_RADIUS, Math.min(Shapes.MAX_RADIUS, raw.radius));
        double height = Math.max(0.0, Math.min(Shapes.MAX_HEIGHT, raw.height));
        double speed = Double.isFinite(raw.speed) ? Math.max(0.0, Math.min(4.0, raw.speed)) : 0.0;
        double rotation = Double.isFinite(raw.rotation) ? Math.max(0.0, Math.min(90.0, raw.rotation)) : 0.0;
        return new EffectStyle.ParticleSpec(shape, particle, check, radius, height, points, count, speed,
                interval, frames, rotation);
    }

    private static EffectStyle.@Nullable SoundSpec parseSound(
            String key, EffectsConfig.Sound raw, List<String> warnings) {
        if (raw == null || raw.key == null || raw.key.isBlank()) {
            return null;
        }
        String value = raw.key.strip();
        String lower = value.toLowerCase(Locale.ROOT);
        boolean enumName;
        String stored;
        if (value.indexOf(':') >= 0) {
            if (!NAMESPACED_KEY.matcher(lower).matches()) {
                warnings.add(prefix(key) + "unknown sound '" + value + "'; use a Sound name or minecraft:key");
                return null;
            }
            enumName = false;
            stored = lower;
        } else {
            String upper = value.toUpperCase(Locale.ROOT);
            // Sound is a registry-backed interface on modern Paper; resolving the constant needs a live
            // server, so only the shape of the name is checked here. The runtime logs an unknown name once.
            if (!upper.matches("[A-Z][A-Z0-9_]*")) {
                warnings.add(prefix(key) + "unknown sound '" + value + "'; use a Sound name or minecraft:key");
                return null;
            }
            enumName = true;
            stored = upper;
        }
        SoundCategory category = SoundCategory.MASTER;
        if (raw.category != null && !raw.category.isBlank()) {
            try {
                category = SoundCategory.valueOf(raw.category.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                warnings.add(prefix(key) + "unknown sound category '" + raw.category + "'; using MASTER");
            }
        }
        float volume = (float) Math.max(0.0, Math.min(10.0, raw.volume));
        float pitch = (float) Math.max(0.5, Math.min(2.0, raw.pitch));
        return new EffectStyle.SoundSpec(stored, enumName, volume, pitch, category);
    }

    private static EffectStyle.@Nullable TitleSpec parseTitle(
            String key, EffectsConfig.Title raw, List<String> warnings) {
        if (raw == null) {
            return null;
        }
        String title = raw.title == null ? "" : raw.title;
        String subtitle = raw.subtitle == null ? "" : raw.subtitle;
        if (title.isBlank() && subtitle.isBlank()) {
            return null;
        }
        int fadeIn = Math.max(0, Math.min(200, raw.fadeInTicks));
        int stay = Math.max(0, Math.min(400, raw.stayTicks));
        int fadeOut = Math.max(0, Math.min(200, raw.fadeOutTicks));
        if (stay <= 0) {
            warnings.add(prefix(key) + "title stayTicks is 0; the title will not be visible");
        }
        return new EffectStyle.TitleSpec(title, subtitle, fadeIn, stay, fadeOut);
    }

    private static EffectStyle.@Nullable FireworkSpec parseFirework(
            String key, EffectsConfig.Firework raw, List<String> warnings) {
        if (raw == null || !raw.enabled) {
            return null;
        }
        FireworkEffect.Type type = FireworkEffect.Type.BALL;
        if (raw.type != null && !raw.type.isBlank()) {
            try {
                type = FireworkEffect.Type.valueOf(raw.type.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                warnings.add(prefix(key) + "unknown firework type '" + raw.type + "'; using BALL");
            }
        }
        List<Color> colours = colours(key, raw.colours, warnings, "colour");
        if (colours.isEmpty()) {
            colours = List.of(Color.WHITE);
        }
        List<Color> fade = colours(key, raw.fade, warnings, "fade colour");
        int power = Math.max(0, Math.min(3, raw.power));
        int detonateAfter = Math.max(0, Math.min(40, raw.detonateAfterTicks));
        return new EffectStyle.FireworkSpec(type, colours, fade, power, detonateAfter, raw.flicker, raw.trail);
    }

    private static List<Color> colours(
            String key, @Nullable List<String> raw, List<String> warnings, String field) {
        List<Color> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String value : raw) {
            if (value == null || value.isBlank()) {
                continue;
            }
            Color colour = ParticleData.colour(value).orElse(null);
            if (colour == null) {
                warnings.add(prefix(key) + "invalid " + field + " '" + value + "'; use #RRGGBB");
            } else {
                out.add(colour);
            }
        }
        return out;
    }

    private static String prefix(String key) {
        return "[" + key + "] ";
    }
}
