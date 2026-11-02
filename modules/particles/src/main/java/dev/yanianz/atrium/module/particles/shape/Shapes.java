package dev.yanianz.atrium.module.particles.shape;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure shape generators. Every method returns a flat array of {@code x, y, z} offsets relative to the
 * player's feet, already rotated by the player yaw. Shapes never touch the world.
 */
public final class Shapes {
    private Shapes() {}

    /**
     * Offsets for one render tick. The length is always a multiple of three; a moving-only style returns
     * an empty array while the player stands still.
     */
    public static double[] offsets(Style style, ShapeFrame frame) {
        if (style == null || frame == null) {
            return new double[0];
        }
        List<Double> out = new ArrayList<>();
        switch (style) {
            case HALO -> halo(frame, out);
            case HELIX -> helix(frame, out);
            case ORBIT -> orbit(frame, out);
            case WINGS -> wings(frame, out);
            case CLOUD -> cloud(frame, out);
            case CAPE -> cape(frame, out);
            case CROWN -> crown(frame, out);
            case SPHERE -> sphere(frame, out);
            case BEAM -> beam(frame, out);
            case PULSE_RING -> pulseRing(frame, out);
            case FOOTSTEPS -> footsteps(frame, out);
            case TRAIL -> trail(frame, out);
            case QUADHELIX -> quadHelix(frame, out);
            case ARROWS_TRAIL -> arrowsTrail(frame, out);
        }
        double[] result = new double[out.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = out.get(i);
        }
        return result;
    }

    /** Number of points in an offsets array. */
    public static int pointCount(double[] offsets) {
        return offsets == null ? 0 : offsets.length / 3;
    }

    // ---- shapes ----

    private static void halo(ShapeFrame f, List<Double> out) {
        int n = points(16, f.density(), 6, 128);
        double phase = f.phase();
        for (int i = 0; i < n; i++) {
            double angle = phase + 2.0 * Math.PI * i / n;
            double x = Math.cos(angle) * f.radius();
            double z = Math.sin(angle) * f.radius();
            double y = 2.05 + 0.03 * Math.sin(angle * 3.0);
            addRotated(f, out, x, y, z);
        }
    }

    private static void helix(ShapeFrame f, List<Double> out) {
        int n = points(24, f.density(), 8, 200);
        double phase = f.phase();
        double height = 2.0;
        for (int i = 0; i < n; i++) {
            double t = n == 1 ? 0.0 : (double) i / (n - 1);
            double angle = phase + t * 4.0 * Math.PI;
            addRotated(f, out, Math.cos(angle) * f.radius(), t * height, Math.sin(angle) * f.radius());
        }
    }

    private static void orbit(ShapeFrame f, List<Double> out) {
        int n = points(8, f.density(), 3, 64);
        double phase = f.phase();
        for (int i = 0; i < n; i++) {
            double angle = phase + 2.0 * Math.PI * i / n;
            addRotated(f, out, Math.cos(angle) * f.radius(), 1.05, Math.sin(angle) * f.radius());
        }
    }

    private static void wings(ShapeFrame f, List<Double> out) {
        List<WingPattern.Point> points = f.wings().points(f.radius());
        int stride = stride(f.density());
        for (int i = 0; i < points.size(); i += stride) {
            WingPattern.Point point = points.get(i);
            addRotated(f, out, point.x(), point.y(), point.z());
        }
    }

    private static void cloud(ShapeFrame f, List<Double> out) {
        int n = points(10, f.density(), 4, 80);
        int cloud = Math.max(1, n / 2);
        double phase = f.phase();
        for (int i = 0; i < cloud; i++) {
            double angle = i * 2.399963;
            double x = Math.cos(angle + phase * 0.2) * f.radius() * 0.8;
            double z = Math.sin(angle + phase * 0.2) * f.radius() * 0.8;
            addRotated(f, out, x, 2.2 + 0.05 * Math.sin(angle * 3.0), z);
        }
        for (int i = cloud; i < n; i++) {
            int drop = i - cloud;
            double angle = drop * 2.399963;
            double fall = 2.1 - ((f.tick() * 0.15 + drop * 0.37) % 2.1);
            addRotated(f, out, Math.cos(angle) * f.radius() * 0.6, fall, Math.sin(angle) * f.radius() * 0.6);
        }
    }

    private static void cape(ShapeFrame f, List<Double> out) {
        int columns = points(7, f.density(), 3, 24);
        int rows = points(9, f.density(), 4, 32);
        double phase = f.phase();
        double halfWidth = f.radius() * 0.5;
        for (int row = 0; row < rows; row++) {
            double t = rows == 1 ? 0.0 : (double) row / (rows - 1);
            double y = Math.max(0.1, 1.75 - t * 1.3);
            double z = -0.22 * f.radius() + 0.04 * Math.sin(phase + t * 4.0);
            for (int column = 0; column < columns; column++) {
                double u = columns == 1 ? 0.0 : (double) column / (columns - 1) - 0.5;
                double sway = 0.05 * Math.sin(phase + t * 5.0 + u * 2.0);
                addRotated(f, out, u * halfWidth * 2.0 + sway, y, z);
            }
        }
    }

    private static void crown(ShapeFrame f, List<Double> out) {
        int n = points(12, f.density(), 6, 64);
        double phase = f.phase();
        for (int i = 0; i < n; i++) {
            double angle = phase + 2.0 * Math.PI * i / n;
            addRotated(f, out, Math.cos(angle) * f.radius(), 2.08, Math.sin(angle) * f.radius());
            if (i % 2 == 0) {
                addRotated(f, out, Math.cos(angle) * f.radius() * 0.92, 2.22, Math.sin(angle) * f.radius() * 0.92);
            }
        }
    }

    private static void sphere(ShapeFrame f, List<Double> out) {
        int n = points(30, f.density(), 12, 200);
        double phase = f.phase();
        double radius = f.radius();
        for (int i = 0; i < n; i++) {
            double y = 1.0 + radius * (1.0 - 2.0 * (i + 0.5) / n);
            double ring = Math.sqrt(Math.max(0.0, radius * radius - (y - 1.0) * (y - 1.0)));
            double angle = phase + i * 2.399963;
            addRotated(f, out, Math.cos(angle) * ring, y, Math.sin(angle) * ring);
        }
    }

    private static void beam(ShapeFrame f, List<Double> out) {
        int n = points(12, f.density(), 4, 100);
        double phase = f.phase();
        double height = Math.max(1.0, f.radius() * 3.0);
        double wiggle = Math.min(0.08, f.radius() * 0.15);
        for (int i = 0; i < n; i++) {
            double t = n == 1 ? 0.0 : (double) i / (n - 1);
            double angle = phase + t * 6.0 * Math.PI;
            addRotated(f, out, Math.cos(angle) * wiggle, t * height, Math.sin(angle) * wiggle);
        }
    }

    private static void pulseRing(ShapeFrame f, List<Double> out) {
        int n = points(14, f.density(), 6, 96);
        double fraction = ((f.tick() * f.speed()) % 360.0) / 360.0;
        double radius = f.radius() * fraction;
        for (int i = 0; i < n; i++) {
            double angle = 2.0 * Math.PI * i / n;
            addRotated(f, out, Math.cos(angle) * radius, 0.1, Math.sin(angle) * radius);
        }
    }

    private static void footsteps(ShapeFrame f, List<Double> out) {
        if (!f.moving()) {
            return;
        }
        double side = (f.tick() / 2) % 2 == 0 ? 1.0 : -1.0;
        double lateral = Math.min(0.18, f.radius());
        addRotated(f, out, lateral * side, 0.05, 0.0);
        addRotated(f, out, -lateral * side, 0.05, 0.12);
    }

    private static void trail(ShapeFrame f, List<Double> out) {
        if (!f.moving()) {
            return;
        }
        int n = points(8, f.density(), 2, 40);
        double phase = f.phase();
        for (int i = 0; i < n; i++) {
            double t = n == 1 ? 0.0 : (double) i / (n - 1);
            double z = -0.25 - t * 1.4;
            double y = 0.1 + t * 0.35 + 0.05 * Math.sin(phase + t * 4.0);
            double x = 0.08 * Math.sin(phase * 0.5 + t * 6.0);
            addRotated(f, out, x, y, z);
        }
    }

    private static void quadHelix(ShapeFrame f, List<Double> out) {
        int n = points(10, f.density(), 3, 80);
        double phase = f.phase();
        for (int strand = 0; strand < 4; strand++) {
            double offset = strand * Math.PI / 2.0;
            for (int i = 0; i < n; i++) {
                double t = n == 1 ? 0.0 : (double) i / (n - 1);
                double angle = phase + t * 3.0 * Math.PI + offset;
                addRotated(f, out, Math.cos(angle) * f.radius(), t * 2.0, Math.sin(angle) * f.radius());
            }
        }
    }

    private static void arrowsTrail(ShapeFrame f, List<Double> out) {
        if (!f.moving()) {
            return;
        }
        int chevrons = points(3, f.density(), 1, 8);
        double phase = f.phase();
        for (int c = 0; c < chevrons; c++) {
            double z = -0.5 - c * 0.55;
            double y = 0.45 + 0.03 * Math.sin(phase + c);
            addRotated(f, out, 0.0, y, z);
            addRotated(f, out, 0.25, y + 0.22, z - 0.16);
            addRotated(f, out, 0.25, y - 0.22, z - 0.16);
            addRotated(f, out, -0.25, y + 0.22, z - 0.16);
            addRotated(f, out, -0.25, y - 0.22, z - 0.16);
        }
    }

    // ---- helpers ----

    private static int points(int base, double density, int min, int max) {
        int value = (int) Math.round(base * density);
        return Math.max(min, Math.min(max, value));
    }

    private static int stride(double density) {
        if (density >= 1.0) {
            return 1;
        }
        return Math.max(1, (int) Math.round(1.0 / density));
    }

    /** Adds one local offset rotated around the Y axis so local +Z points where the player looks. */
    private static void addRotated(ShapeFrame f, List<Double> out, double x, double y, double z) {
        double c = Math.cos(f.yawRadians());
        double s = Math.sin(f.yawRadians());
        out.add(x * c - z * s);
        out.add(y);
        out.add(x * s + z * c);
    }
}
