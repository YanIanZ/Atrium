package dev.yanianz.atrium.module.particles;

import dev.yanianz.atrium.module.particles.shape.Style;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/particles.yml */
@ConfigSerializable
public class ParticlesConfig {
    @Comment("Ticks between particle renders for each player with an active effect. 1 through 20;\n"
            + "2 keeps the effect smooth while staying cheap. One timer runs per player, on their own thread.")
    public int renderIntervalTicks = 2;

    @Comment("Maximum particles one player may spawn per second, across all their active effects.\n"
            + "20 through 20000. Excess points are skipped, never queued.")
    public int maxParticlesPerSecond = 600;

    @Comment("When the region TPS is below this value the shape density is multiplied by throttleMultiplier.\n"
            + "1.0 through 20.0.")
    public double tpsThreshold = 18.0;

    @Comment("Density multiplier while the region is lagging. 0.05 through 1.0.")
    public double throttleMultiplier = 0.5;

    @Comment("Active effects a player may use without a numeric permission. atrium.particles.slots.<n>\n"
            + "raises it and atrium.particles.slots.unlimited removes the limit. 0 through 16.")
    public int defaultSlots = 2;

    @Comment("Skip rendering while the player is AFK (requires the afk module).")
    public boolean skipAfk = true;

    @Comment("Skip rendering while the player is vanished (requires the vanish module).")
    public boolean skipVanished = true;

    @Comment("Horizontal movement in blocks that counts as 'moving' for trails and footsteps. 0.001 through 2.0.")
    public double movementThreshold = 0.02;

    @Comment("Size of DUST and DUST_COLOR_TRANSITION particles. 0.1 through 4.0.")
    public double dustSize = 1.0;

    @Comment("Item used by the ITEM particle and block used by BLOCK/FALLING_DUST.")
    public String itemParticleMaterial = "DIAMOND";
    public String blockParticleMaterial = "STONE";

    @Comment("Default colour for a new effect when the style does not set one. Hex (#RRGGBB) or a named colour.")
    public String defaultColour = "#5FB3A1";
    public String defaultColour2 = "#F2C46D";
    @Comment("Default colour mode: fixed, rainbow or gradient.")
    public String defaultColourMode = "fixed";

    @Comment("Colours offered by the colour picker, as hex (#RRGGBB) or named colours.")
    public List<String> fixedColours = new ArrayList<>(List.of(
            "#5FB3A1", "#3B7F9A", "#F2C46D", "#E5534B", "#6BCB77", "#A46BF5", "#FF9F45", "#FFFFFF"));

    @Comment("Wings bitmap used by the wings style. '#' draws a particle, '.' leaves the cell empty.\n"
            + "Rows are drawn top to bottom, centred on the player's spine.")
    public List<String> wingPattern = new ArrayList<>(List.of(
            "#.........#",
            "##.......##",
            "###.....###",
            "####...####",
            ".#########.",
            "..#######..",
            "...#####..."));

    @Comment("Per-style defaults. Every style accepts particle, colour, colour2, colour-mode (fixed,\n"
            + "rainbow or gradient), radius, density and speed (degrees per render tick).\n"
            + "A radius, density or speed of 0 keeps the built-in value for that style.")
    public Map<String, StyleSettings> styles = defaultStyles();

    @Comment("Named looks for /particles preset <name>. A preset replaces every active effect of the player.")
    public Map<String, Preset> presets = defaultPresets();

    @ConfigSerializable
    public static class StyleSettings {
        @Comment("Particle name, e.g. dust, end_rod, flame, heart.")
        public String particle = "dust";
        @Comment("Primary colour (hex or named).")
        public String colour = "";
        @Comment("Second colour for gradients and DUST_COLOR_TRANSITION.")
        public String colour2 = "";
        @Comment("fixed, rainbow or gradient.")
        public String colourMode = "fixed";
        @Comment("Shape radius in blocks. 0 keeps the built-in default.")
        public double radius = 0.0;
        @Comment("Point density multiplier. 0 keeps the built-in default.")
        public double density = 0.0;
        @Comment("Animation speed in degrees per render tick. 0 keeps the built-in default.")
        public double speed = 0.0;
    }

    @ConfigSerializable
    public static class Preset {
        @Comment("Effects of this preset. Only as many as the player has slots are applied.")
        public List<PresetEffect> effects = new ArrayList<>();
    }

    @ConfigSerializable
    public static class PresetEffect {
        @Comment("Style key, e.g. halo, wings, trail.")
        public String style = "halo";
        @Comment("Particle name, e.g. dust, end_rod, flame.")
        public String particle = "dust";
        @Comment("Primary colour (hex or named).")
        public String colour = "";
        @Comment("Second colour for gradients.")
        public String colour2 = "";
        @Comment("fixed, rainbow or gradient.")
        public String colourMode = "fixed";
        public double radius = 0.0;
        public double density = 0.0;
        public double speed = 0.0;
    }

    /** Settings for a style, falling back to the enum defaults when the map has no entry. */
    public StyleSettings style(Style style) {
        if (styles != null) {
            for (Map.Entry<String, StyleSettings> entry : styles.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(style.key()) && entry.getValue() != null) {
                    return entry.getValue();
                }
            }
        }
        StyleSettings fallback = new StyleSettings();
        fallback.colour = defaultColour;
        fallback.colour2 = defaultColour2;
        fallback.colourMode = defaultColourMode;
        return fallback;
    }

    public double radius(Style style) {
        StyleSettings settings = style(style);
        double value = settings.radius > 0 ? settings.radius : style.defaultRadius();
        return Math.max(0.05, Math.min(8.0, value));
    }

    public double density(Style style) {
        StyleSettings settings = style(style);
        double value = settings.density > 0 ? settings.density : style.defaultDensity();
        return Math.max(0.05, Math.min(8.0, value));
    }

    public double speed(Style style) {
        StyleSettings settings = style(style);
        double value = settings.speed > 0 ? settings.speed : style.defaultSpeed();
        return Math.max(0.0, Math.min(360.0, value));
    }

    private static Map<String, StyleSettings> defaultStyles() {
        Map<String, StyleSettings> map = new LinkedHashMap<>();
        for (Style style : Style.values()) {
            StyleSettings settings = new StyleSettings();
            settings.particle = switch (style) {
                case HELIX, BEAM, SPHERE -> "end_rod";
                case ORBIT, CROWN, QUADHELIX -> "firework";
                case CLOUD -> "cloud";
                default -> "dust";
            };
            settings.colour = defaultColourFor(style);
            settings.colour2 = "#F2C46D";
            settings.colourMode = "fixed";
            map.put(style.key(), settings);
        }
        return map;
    }

    private static String defaultColourFor(Style style) {
        return switch (style) {
            case HALO, CROWN -> "#F2C46D";
            case HELIX, BEAM, QUADHELIX -> "#A46BF5";
            case ORBIT, SPHERE -> "#5FB3A1";
            case WINGS, CAPE -> "#FFFFFF";
            case CLOUD -> "#9FD8FF";
            case PULSE_RING -> "#6BCB77";
            case FOOTSTEPS, TRAIL, ARROWS_TRAIL -> "#FF9F45";
        };
    }

    private static Map<String, Preset> defaultPresets() {
        Map<String, Preset> map = new LinkedHashMap<>();
        map.put("angel", preset(
                effect("halo", "dust", "#F2C46D", "#FFFFFF", "gradient"),
                effect("wings", "dust", "#FFFFFF", "#9FD8FF", "gradient")));
        map.put("storm", preset(
                effect("cloud", "cloud", "#9FD8FF", "#FFFFFF", "fixed"),
                effect("beam", "end_rod", "#A46BF5", "#FFFFFF", "gradient")));
        map.put("runner", preset(
                effect("footsteps", "dust", "#FF9F45", "#F2C46D", "fixed"),
                effect("trail", "flame", "#FF9F45", "#E5534B", "gradient")));
        return map;
    }

    private static Preset preset(PresetEffect... effects) {
        Preset preset = new Preset();
        preset.effects = new ArrayList<>(List.of(effects));
        return preset;
    }

    private static PresetEffect effect(String style, String particle, String colour, String colour2, String mode) {
        PresetEffect effect = new PresetEffect();
        effect.style = style;
        effect.particle = particle;
        effect.colour = colour;
        effect.colour2 = colour2;
        effect.colourMode = mode;
        return effect;
    }

    /** Validates values after load; throws so /atrium reload reports the file instead of failing later. */
    public void validate() {
        if (renderIntervalTicks < 1 || renderIntervalTicks > 20) {
            throw new IllegalArgumentException("particles.renderIntervalTicks must be 1-20");
        }
        if (maxParticlesPerSecond < 20 || maxParticlesPerSecond > 20_000) {
            throw new IllegalArgumentException("particles.maxParticlesPerSecond must be 20-20000");
        }
        if (!(tpsThreshold >= 1.0 && tpsThreshold <= 20.0)) {
            throw new IllegalArgumentException("particles.tpsThreshold must be 1.0-20.0");
        }
        if (!(throttleMultiplier >= 0.05 && throttleMultiplier <= 1.0)) {
            throw new IllegalArgumentException("particles.throttleMultiplier must be 0.05-1.0");
        }
        if (defaultSlots < 0 || defaultSlots > 16) {
            throw new IllegalArgumentException("particles.defaultSlots must be 0-16");
        }
        if (!(dustSize >= 0.1 && dustSize <= 4.0)) {
            throw new IllegalArgumentException("particles.dustSize must be 0.1-4.0");
        }
        if (!(movementThreshold > 0.0 && movementThreshold <= 2.0)) {
            throw new IllegalArgumentException("particles.movementThreshold must be 0.001-2.0");
        }
        if (styles == null) {
            styles = defaultStyles();
        }
        if (presets == null) {
            presets = defaultPresets();
        }
        if (wingPattern == null || wingPattern.isEmpty()) {
            wingPattern = new ArrayList<>(List.of("#.........#"));
        }
        if (fixedColours == null || fixedColours.isEmpty()) {
            fixedColours = new ArrayList<>(List.of("#5FB3A1"));
        }
        if (fixedColours.size() > 21) {
            throw new IllegalArgumentException("particles.fixedColours may contain at most 21 entries");
        }
        // Invalid wing rows are rejected when the pattern is built; store the validated copy.
        wingPattern = new ArrayList<>(dev.yanianz.atrium.module.particles.shape.WingPattern.parse(wingPattern).rows());
        if (ParticleColours.Mode.byName(defaultColourMode) == null) {
            defaultColourMode = "fixed";
        }
        for (Map.Entry<String, StyleSettings> entry : styles.entrySet()) {
            if (Style.byName(entry.getKey()) == null) {
                throw new IllegalArgumentException("particles.styles has unknown style '" + entry.getKey() + "'");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("particles.styles." + entry.getKey() + " is empty");
            }
            StyleSettings settings = entry.getValue();
            if (settings.radius < 0 || settings.radius > 8) {
                throw new IllegalArgumentException("particles.styles." + entry.getKey() + ".radius must be 0-8");
            }
            if (settings.density < 0 || settings.density > 8) {
                throw new IllegalArgumentException("particles.styles." + entry.getKey() + ".density must be 0-8");
            }
            if (settings.speed < 0 || settings.speed > 360) {
                throw new IllegalArgumentException("particles.styles." + entry.getKey() + ".speed must be 0-360");
            }
        }
    }

    /** Applies the configured defaults to a user-typed colour string. */
    public ParticleColours colours(Style style, String value) {
        StyleSettings settings = style(style);
        var primary = ParticleColours.parse(
                settings.colour == null || settings.colour.isBlank() ? defaultColour : settings.colour,
                ParticleColours.parse(defaultColour, org.bukkit.Color.WHITE));
        var secondary = ParticleColours.parse(
                settings.colour2 == null || settings.colour2.isBlank() ? defaultColour2 : settings.colour2,
                ParticleColours.parse(defaultColour2, org.bukkit.Color.WHITE));
        ParticleColours.Mode mode = ParticleColours.Mode.byName(settings.colourMode);
        if (mode == null) {
            mode = ParticleColours.Mode.byName(defaultColourMode);
        }
        if (mode == null) {
            mode = ParticleColours.Mode.FIXED;
        }
        if (value == null || value.isBlank()) {
            return new ParticleColours(mode, primary, secondary);
        }
        String cleaned = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        ParticleColours.Mode requested = ParticleColours.Mode.byName(cleaned);
        if (requested != null) {
            return new ParticleColours(requested, primary, secondary);
        }
        var parsed = dev.yanianz.atrium.core.message.Theme.parseColor(value);
        if (parsed == null) {
            return new ParticleColours(mode, primary, secondary);
        }
        return new ParticleColours(ParticleColours.Mode.FIXED, org.bukkit.Color.fromRGB(parsed.value()), secondary);
    }
}
