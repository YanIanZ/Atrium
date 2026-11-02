package dev.yanianz.atrium.module.armorstand;

import org.bukkit.util.EulerAngle;

/**
 * Angle arithmetic for the editor. Steps are given in degrees and converted with {@link Math#toRadians}
 * (no integer division, so a 1° step really is 1° and no step can produce an infinite angle). Angles
 * are kept in [-180°, 180°) so they never accumulate without bound.
 */
final class PoseMath {
    /** Largest accepted single step in degrees. */
    static final double MAX_STEP_DEGREES = 180.0;
    /** Stored/compared precision: hundredths of a degree. */
    static final double SCALE = 100.0;

    private PoseMath() {}

    /** Converts a step in degrees to radians; rejects non-finite, zero and out-of-range steps. */
    static double stepRadians(double degrees) {
        if (!Double.isFinite(degrees) || degrees == 0 || Math.abs(degrees) > MAX_STEP_DEGREES) {
            throw new IllegalArgumentException("Step must be a finite non-zero angle within ±180°: " + degrees);
        }
        return Math.toRadians(degrees);
    }

    /** Wraps radians into [-π, π). */
    static double normalizeRadians(double radians) {
        if (!Double.isFinite(radians)) {
            throw new IllegalArgumentException("Angle is not finite: " + radians);
        }
        double twoPi = Math.PI * 2;
        double r = radians % twoPi;
        if (r >= Math.PI) {
            r -= twoPi;
        } else if (r < -Math.PI) {
            r += twoPi;
        }
        return r;
    }

    /** Wraps degrees into [-180, 180) and rounds to {@code 1/SCALE}; the single rounding rule for save and compare. */
    static double canonicalDegrees(double degrees) {
        if (!Double.isFinite(degrees)) {
            throw new IllegalArgumentException("Angle is not finite: " + degrees);
        }
        double d = degrees % 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        } else if (d < -180.0) {
            d += 360.0;
        }
        double rounded = Math.round(d * SCALE) / SCALE;
        if (rounded >= 180.0) {
            rounded -= 360.0;
        }
        // Avoid "-0.00" in storage and equality surprises.
        return rounded == 0.0 ? 0.0 : rounded;
    }

    /** Rotates one axis of an angle by {@code stepDegrees}. */
    static EulerAngle rotate(EulerAngle angle, Pose.Axis axis, double stepDegrees) {
        double delta = stepRadians(stepDegrees);
        return switch (axis) {
            case X -> angle.setX(normalizeRadians(angle.getX() + delta));
            case Y -> angle.setY(normalizeRadians(angle.getY() + delta));
            case Z -> angle.setZ(normalizeRadians(angle.getZ() + delta));
        };
    }

    /** Equality under the storage rounding. */
    static boolean same(EulerAngle a, EulerAngle b) {
        return Pose.Angles.of(a).equals(Pose.Angles.of(b));
    }
}
