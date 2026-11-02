package dev.yanianz.atrium.module.messaging;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The colour palettes behind {@code /colorpicker}. Pure data so the swatch count, the hex format and
 * the "insert this exact tag" contract are unit tested without a running server.
 */
final class ColorPicker {
    private ColorPicker() {}

    /**
     * @param label the visible name (named colour) or hex value (grid)
     * @param rgb   packed 0xRRGGBB colour used to draw the swatch
     * @param tag   the MiniMessage tag inserted into chat, including the angle brackets
     */
    record Swatch(String label, int rgb, String tag) {
        String hex() {
            return String.format(Locale.ROOT, "#%06x", rgb);
        }
    }

    static final List<Swatch> NAMED = List.of(
            named("black", 0x000000),
            named("dark_blue", 0x0000AA),
            named("dark_green", 0x00AA00),
            named("dark_aqua", 0x00AAAA),
            named("dark_red", 0xAA0000),
            named("dark_purple", 0xAA00AA),
            named("gold", 0xFFAA00),
            named("gray", 0xAAAAAA),
            named("dark_gray", 0x555555),
            named("blue", 0x5555FF),
            named("green", 0x55FF55),
            named("aqua", 0x55FFFF),
            named("red", 0xFF5555),
            named("light_purple", 0xFF55FF),
            named("yellow", 0xFFFF55),
            named("white", 0xFFFFFF));

    /** 24 evenly spaced hues, inserted as {@code <#rrggbb>}. */
    static List<Swatch> hexGrid() {
        List<Swatch> swatches = new ArrayList<>(24);
        for (int i = 0; i < 24; i++) {
            int rgb = hsv(i * 15.0, 0.85, 0.95);
            String hex = String.format(Locale.ROOT, "#%06x", rgb);
            swatches.add(new Swatch(hex, rgb, "<" + hex + ">"));
        }
        return List.copyOf(swatches);
    }

    private static Swatch named(String name, int rgb) {
        return new Swatch(name, rgb, "<" + name + ">");
    }

    /** Hue in degrees, saturation and value in 0..1; returns packed 0xRRGGBB. */
    static int hsv(double hue, double saturation, double value) {
        double h = ((hue % 360) + 360) % 360;
        double s = Math.max(0, Math.min(1, saturation));
        double v = Math.max(0, Math.min(1, value));
        double c = v * s;
        double x = c * (1 - Math.abs((h / 60.0) % 2 - 1));
        double m = v - c;
        double r;
        double g;
        double b;
        if (h < 60) {
            r = c; g = x; b = 0;
        } else if (h < 120) {
            r = x; g = c; b = 0;
        } else if (h < 180) {
            r = 0; g = c; b = x;
        } else if (h < 240) {
            r = 0; g = x; b = c;
        } else if (h < 300) {
            r = x; g = 0; b = c;
        } else {
            r = c; g = 0; b = x;
        }
        int red = (int) Math.round((r + m) * 255);
        int green = (int) Math.round((g + m) * 255);
        int blue = (int) Math.round((b + m) * 255);
        return (red << 16) | (green << 8) | blue;
    }
}
