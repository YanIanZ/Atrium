package dev.yanianz.atrium.module.worldrules;

import org.bukkit.Material;

/** Pure hue rotation and HSV to RGB conversion for the animated leather armour. */
final class LeatherColors {
    private LeatherColors() {}

    static int wrapHue(int hue) {
        return Math.floorMod(hue, 360);
    }

    static int nextHue(int hue, int step) {
        return wrapHue(hue + step);
    }

    static boolean leather(Material type) {
        if (type == null) {
            return false;
        }
        return switch (type) {
            case LEATHER_HELMET, LEATHER_CHESTPLATE, LEATHER_LEGGINGS, LEATHER_BOOTS -> true;
            default -> false;
        };
    }

    /** HSV to packed RGB, with hue in degrees and saturation/brightness as percentages. */
    static int rgb(int hue, int saturationPercent, int brightnessPercent) {
        double h = (double) wrapHue(hue) / 60.0;
        double s = clamp01(saturationPercent / 100.0);
        double v = clamp01(brightnessPercent / 100.0);
        int sector = (int) Math.floor(h) % 6;
        double f = h - Math.floor(h);
        int p = channel(v * (1.0 - s));
        int q = channel(v * (1.0 - s * f));
        int t = channel(v * (1.0 - s * (1.0 - f)));
        int full = channel(v);
        int[] rgb = switch (sector) {
            case 0 -> new int[] {full, t, p};
            case 1 -> new int[] {q, full, p};
            case 2 -> new int[] {p, full, t};
            case 3 -> new int[] {p, q, full};
            case 4 -> new int[] {t, p, full};
            default -> new int[] {full, p, q};
        };
        return (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static int channel(double value) {
        return (int) Math.round(clamp01(value) * 255.0);
    }
}
