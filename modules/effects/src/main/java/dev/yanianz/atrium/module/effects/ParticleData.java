package dev.yanianz.atrium.module.effects;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.jspecify.annotations.Nullable;

/**
 * Validates particle types against the data the server actually accepts, and builds the immutable data
 * object once per definition. A particle that needs a block or item cannot be configured from a plain
 * name, so it is rejected with a clear message instead of throwing at spawn time.
 */
public final class ParticleData {
    private ParticleData() {}

    /** Result of validating one particle: the ready-to-spawn data, or a human readable reason. */
    public record Check(Particle.@Nullable DustOptions dust, Particle.@Nullable DustTransition transition,
                        @Nullable Color colour, @Nullable String error) {
        public boolean valid() {
            return error == null;
        }

        public boolean hasData() {
            return dust != null || transition != null || colour != null;
        }
    }

    public static final Check NO_DATA = new Check(null, null, null, null);

    public static @Nullable Particle particle(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Particle.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static List<String> supportedNames() {
        return java.util.Arrays.stream(Particle.values())
                .filter(ParticleData::supported)
                .map(Enum::name)
                .sorted()
                .toList();
    }

    public static boolean supported(Particle particle) {
        Class<?> type = particle.getDataType();
        return type == Void.class || type == Void.TYPE
                || type == Particle.DustOptions.class
                || type == Particle.DustTransition.class
                || type == Color.class;
    }

    /**
     * Parses a {@code #RRGGBB} or {@code RRGGBB} colour. Named colours are intentionally not accepted:
     * a typo would otherwise silently fall back to a wrong-looking default.
     */
    public static Optional<Color> colour(@Nullable String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = raw.strip();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        if (value.length() != 6) {
            return Optional.empty();
        }
        try {
            int rgb = Integer.parseInt(value, 16);
            return Optional.of(Color.fromRGB(rgb));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public static String formatColour(Color colour) {
        return String.format(Locale.ROOT, "#%06x", colour.asRGB() & 0xFFFFFF);
    }

    /**
     * Builds the data object for a particle.
     *
     * @param primary   required by every particle whose data type is a colour
     * @param secondary fade colour for {@link Particle#DUST_COLOR_TRANSITION}; defaults to {@code primary}
     * @param size      dust size, clamped to 0.1..4
     */
    public static Check check(
            Particle particle, @Nullable Color primary, @Nullable Color secondary, double size) {
        Class<?> type = particle.getDataType();
        if (type == Void.class || type == Void.TYPE) {
            return NO_DATA;
        }
        float clampedSize = (float) Math.max(0.1, Math.min(4.0, size));
        if (type == Particle.DustOptions.class) {
            if (primary == null) {
                return new Check(null, null, null,
                        "particle " + particle.name() + " needs a colour (for example #ff8800)");
            }
            return new Check(new Particle.DustOptions(primary, clampedSize), null, null, null);
        }
        if (type == Particle.DustTransition.class) {
            if (primary == null) {
                return new Check(null, null, null,
                        "particle " + particle.name() + " needs a colour (for example #ff8800)");
            }
            Color to = secondary == null ? primary : secondary;
            return new Check(null, new Particle.DustTransition(primary, to, clampedSize), null, null);
        }
        if (type == Color.class) {
            if (primary == null) {
                return new Check(null, null, null,
                        "particle " + particle.name() + " needs a colour (for example #ff8800)");
            }
            return new Check(null, null, primary, null);
        }
        return new Check(null, null, null,
                "particle " + particle.name() + " needs " + type.getSimpleName() + " data, which is not supported");
    }
}
