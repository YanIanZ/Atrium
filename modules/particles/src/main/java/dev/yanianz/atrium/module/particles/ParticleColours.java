package dev.yanianz.atrium.module.particles;

import dev.yanianz.atrium.core.message.Theme;
import java.util.Locale;
import org.bukkit.Color;
import org.jspecify.annotations.Nullable;

/**
 * Colour configuration of one effect: fixed, a cycling rainbow or a two-colour gradient along the shape.
 * Pure math, so it can be unit-tested.
 */
public record ParticleColours(Mode mode, Color primary, Color secondary) {
    public enum Mode {
        FIXED,
        RAINBOW,
        GRADIENT;

        public static @Nullable Mode byName(String name) {
            if (name == null) {
                return null;
            }
            String normalized = name.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            for (Mode mode : values()) {
                if (mode.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                    return mode;
                }
            }
            return null;
        }
    }

    public ParticleColours {
        if (mode == null) {
            mode = Mode.FIXED;
        }
        if (primary == null) {
            primary = Color.fromRGB(0x5FB3A1);
        }
        if (secondary == null) {
            secondary = primary;
        }
    }

    /** Colour of a point. {@code index} runs left to right along the shape, {@code tick} drives rainbow cycling. */
    public Color at(int index, int total, int tick) {
        return switch (mode) {
            case FIXED -> primary;
            case RAINBOW -> {
                double hue = ((tick * 4.0) + 360.0 * index / Math.max(1, total)) % 360.0;
                yield fromHsb(hue);
            }
            case GRADIENT -> gradient(index, total);
        };
    }

    private Color gradient(int index, int total) {
        if (total <= 1 || index <= 0) {
            return primary;
        }
        if (index >= total - 1) {
            return secondary;
        }
        double ratio = (double) index / (total - 1);
        return Color.fromRGB(
                (int) Math.round(primary.getRed() + (secondary.getRed() - primary.getRed()) * ratio),
                (int) Math.round(primary.getGreen() + (secondary.getGreen() - primary.getGreen()) * ratio),
                (int) Math.round(primary.getBlue() + (secondary.getBlue() - primary.getBlue()) * ratio));
    }

    /** HSB to RGB without AWT; hue is in degrees. */
    private static Color fromHsb(double hueDegrees) {
        double hue = ((hueDegrees % 360.0) + 360.0) % 360.0;
        float scaled = (float) (hue / 60.0);
        int sector = (int) Math.floor(scaled) % 6;
        float fraction = scaled - (float) Math.floor(scaled);
        float r;
        float g;
        float b;
        switch (sector) {
            case 0 -> {
                r = 1.0f;
                g = fraction;
                b = 0.0f;
            }
            case 1 -> {
                r = 1.0f - fraction;
                g = 1.0f;
                b = 0.0f;
            }
            case 2 -> {
                r = 0.0f;
                g = 1.0f;
                b = fraction;
            }
            case 3 -> {
                r = 0.0f;
                g = 1.0f - fraction;
                b = 1.0f;
            }
            case 4 -> {
                r = fraction;
                g = 0.0f;
                b = 1.0f;
            }
            default -> {
                r = 1.0f;
                g = 0.0f;
                b = 1.0f - fraction;
            }
        }
        return Color.fromRGB(Math.round(r * 255.0f), Math.round(g * 255.0f), Math.round(b * 255.0f));
    }

    public ParticleColours withMode(Mode newMode) {
        return new ParticleColours(newMode, primary, secondary);
    }

    public ParticleColours withPrimary(Color colour) {
        return new ParticleColours(mode, colour, secondary);
    }

    /** Parses a trusted config colour; falls back to {@code fallback} when the value is not valid. */
    public static Color parse(String value, Color fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        var text = Theme.parseColor(value);
        return text == null ? fallback : Color.fromRGB(text.value());
    }

    public static String hex(Color colour) {
        return String.format(Locale.ROOT, "#%06X", colour.asRGB());
    }
}
