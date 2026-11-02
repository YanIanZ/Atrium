package dev.yanianz.atrium.module.effects;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure geometry for the built-in particle shapes. Every shape returns offsets relative to the effect
 * centre, always with {@code y >= 0}, so an effect placed at a block never spawns underground.
 *
 * <p>The output is deterministic for a given phase, which keeps unit tests stable and makes a fixed
 * effect animate the same way on every server restart. Angles are converted from degrees exactly once, and
 * divided by a point count that could be zero (B-P1, B-P2 in the report); both mistakes are avoided
 * here by working in radians only and clamping every parameter.
 */
public final class Shapes {
    public static final int MIN_POINTS = 1;
    public static final int MAX_POINTS = 256;
    public static final double MIN_RADIUS = 0.05;
    public static final double MAX_RADIUS = 32.0;
    public static final double MAX_HEIGHT = 32.0;

    private static final double GOLDEN_ANGLE = Math.PI * (3.0 - Math.sqrt(5.0));

    private Shapes() {}

    /** One particle position relative to the effect centre. */
    public record Offset(double x, double y, double z) {}

    /**
     * One animation frame of {@code shape}.
     *
     * @param radius   horizontal radius in blocks
     * @param height   vertical extent used by {@link Shape#COLUMN} and {@link Shape#SPIRAL}
     * @param points   number of positions, clamped to {@value #MIN_POINTS}..{@value #MAX_POINTS}
     * @param rotation degrees the shape turns per phase step
     * @param phase    animation frame, at least zero
     */
    public static List<Offset> frame(
            Shape shape, double radius, double height, int points, double rotation, int phase) {
        int count = clamp(points, MIN_POINTS, MAX_POINTS);
        double r = clamp(radius, MIN_RADIUS, MAX_RADIUS);
        double h = clamp(height, 0.0, MAX_HEIGHT);
        double spin = Math.toRadians(rotation * Math.max(0, phase));
        List<Offset> out = new ArrayList<>(count);
        switch (shape) {
            case BURST -> burst(out, count, r, spin);
            case RING -> ring(out, count, r, spin);
            case SPHERE -> sphere(out, count, r, spin);
            case COLUMN -> column(out, count, r, h, spin);
            case SPIRAL -> spiral(out, count, r, h, spin);
        }
        return out;
    }

    private static void burst(List<Offset> out, int count, double r, double spin) {
        for (int i = 0; i < count; i++) {
            // Two incommensurable sequences give a scattered look without a Random shared between frames.
            double u = fraction(0.6180339887498949 * (i + 1));
            double v = fraction(0.7548776662466927 * (i + 2));
            double angle = Math.PI * 2.0 * u + spin;
            double ring = r * Math.sqrt(Math.max(0.0, 1.0 - v * v)) * (0.35 + 0.65 * u);
            out.add(new Offset(
                    Math.cos(angle) * ring,
                    // Keep the burst above the origin: a half sphere, not a full one.
                    r * 0.5 * (1.0 + (2.0 * v - 1.0)),
                    Math.sin(angle) * ring));
        }
    }

    private static void ring(List<Offset> out, int count, double r, double spin) {
        for (int i = 0; i < count; i++) {
            double angle = Math.PI * 2.0 * i / count + spin;
            out.add(new Offset(Math.cos(angle) * r, 0.0, Math.sin(angle) * r));
        }
    }

    private static void sphere(List<Offset> out, int count, double r, double spin) {
        for (int i = 0; i < count; i++) {
            // Fibonacci sphere: evenly spaced directions, centred at y = r.
            double unitY = 1.0 - 2.0 * (i + 0.5) / count;
            double ring = Math.sqrt(Math.max(0.0, 1.0 - unitY * unitY));
            double angle = GOLDEN_ANGLE * i + spin;
            out.add(new Offset(
                    Math.cos(angle) * ring * r,
                    (unitY + 1.0) * r,
                    Math.sin(angle) * ring * r));
        }
    }

    private static void column(List<Offset> out, int count, double r, double h, double spin) {
        double last = Math.max(1, count - 1);
        for (int i = 0; i < count; i++) {
            double angle = GOLDEN_ANGLE * i + spin;
            out.add(new Offset(
                    Math.cos(angle) * r,
                    h * i / last,
                    Math.sin(angle) * r));
        }
    }

    private static void spiral(List<Offset> out, int count, double r, double h, double spin) {
        double last = Math.max(1, count - 1);
        double turns = 3.0;
        for (int i = 0; i < count; i++) {
            double progress = i / last;
            double angle = Math.PI * 2.0 * turns * progress + spin;
            out.add(new Offset(Math.cos(angle) * r, h * progress, Math.sin(angle) * r));
        }
    }

    /** Positive fractional part; safe for negative input. */
    static double fraction(double value) {
        double result = value - Math.floor(value);
        return result < 0 ? result + 1.0 : result;
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
