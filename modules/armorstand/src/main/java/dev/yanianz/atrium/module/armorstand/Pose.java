package dev.yanianz.atrium.module.armorstand;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.entity.ArmorStand;
import org.bukkit.util.EulerAngle;

/**
 * Pose of all six armor stand parts in canonical degrees (see {@link PoseMath#canonicalDegrees}). Saving,
 * loading and comparing all go through the same rounding, so a saved pose loads back to an equal pose.
 */
record Pose(Map<Part, Angles> parts) {
    enum Part {
        HEAD, BODY, LEFT_ARM, RIGHT_ARM, LEFT_LEG, RIGHT_LEG;

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Part parse(String key) {
            return valueOf(key.trim().toUpperCase(Locale.ROOT));
        }

        EulerAngle get(ArmorStand stand) {
            return switch (this) {
                case HEAD -> stand.getHeadPose();
                case BODY -> stand.getBodyPose();
                case LEFT_ARM -> stand.getLeftArmPose();
                case RIGHT_ARM -> stand.getRightArmPose();
                case LEFT_LEG -> stand.getLeftLegPose();
                case RIGHT_LEG -> stand.getRightLegPose();
            };
        }

        void set(ArmorStand stand, EulerAngle angle) {
            switch (this) {
                case HEAD -> stand.setHeadPose(angle);
                case BODY -> stand.setBodyPose(angle);
                case LEFT_ARM -> stand.setLeftArmPose(angle);
                case RIGHT_ARM -> stand.setRightArmPose(angle);
                case LEFT_LEG -> stand.setLeftLegPose(angle);
                case RIGHT_LEG -> stand.setRightLegPose(angle);
            }
        }
    }

    enum Axis { X, Y, Z }

    /** Canonical degrees of one part. Construct through {@link #of} to apply the rounding. */
    record Angles(double x, double y, double z) {
        static final Angles ZERO = new Angles(0, 0, 0);

        static Angles of(double xDegrees, double yDegrees, double zDegrees) {
            return new Angles(PoseMath.canonicalDegrees(xDegrees), PoseMath.canonicalDegrees(yDegrees),
                    PoseMath.canonicalDegrees(zDegrees));
        }

        static Angles of(EulerAngle radians) {
            return of(Math.toDegrees(radians.getX()), Math.toDegrees(radians.getY()), Math.toDegrees(radians.getZ()));
        }

        EulerAngle toEuler() {
            return new EulerAngle(Math.toRadians(x), Math.toRadians(y), Math.toRadians(z));
        }

        double get(Axis axis) {
            return switch (axis) {
                case X -> x;
                case Y -> y;
                case Z -> z;
            };
        }
    }

    /** Vanilla's default pose of a freshly placed armor stand. */
    static final Pose DEFAULT = new Pose(java.util.Map.of(
            Part.LEFT_ARM, new Angles(-10, 0, -10), Part.RIGHT_ARM, new Angles(-15, 0, 10),
            Part.LEFT_LEG, new Angles(-1, 0, -1), Part.RIGHT_LEG, new Angles(1, 0, 1)));

    Pose {
        EnumMap<Part, Angles> copy = new EnumMap<>(Part.class);
        for (Part part : Part.values()) {
            Angles a = parts.get(part);
            copy.put(part, a == null ? Angles.ZERO : Angles.of(a.x(), a.y(), a.z()));
        }
        parts = java.util.Collections.unmodifiableMap(copy);
    }

    static Pose of(ArmorStand stand) {
        EnumMap<Part, Angles> map = new EnumMap<>(Part.class);
        for (Part part : Part.values()) {
            map.put(part, Angles.of(part.get(stand)));
        }
        return new Pose(map);
    }

    void apply(ArmorStand stand) {
        parts.forEach((part, angles) -> part.set(stand, angles.toEuler()));
    }

    Angles get(Part part) {
        return parts.get(part);
    }

    /** {@code head:0.00,0.00,0.00;body:...}, always with {@link Locale#ROOT}. */
    String encode() {
        List<String> out = new ArrayList<>();
        parts.forEach((part, a) -> out.add(String.format(Locale.ROOT, "%s:%.2f,%.2f,%.2f", part.key(), a.x(), a.y(), a.z())));
        return String.join(";", out);
    }

    /** Parses {@link #encode()}; missing parts are zero. Throws {@link IllegalArgumentException} when malformed. */
    static Pose decode(String encoded) {
        EnumMap<Part, Angles> map = new EnumMap<>(Part.class);
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("Empty pose");
        }
        for (String entry : encoded.split(";")) {
            int colon = entry.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("Malformed pose entry: " + entry);
            }
            Part part = Part.parse(entry.substring(0, colon));
            String[] values = entry.substring(colon + 1).split(",");
            if (values.length != 3) {
                throw new IllegalArgumentException("Pose entry needs three angles: " + entry);
            }
            double[] d = new double[3];
            for (int i = 0; i < 3; i++) {
                d[i] = Double.parseDouble(values[i].trim());
                if (!Double.isFinite(d[i])) {
                    throw new IllegalArgumentException("Angle is not finite: " + entry);
                }
            }
            map.put(part, Angles.of(d[0], d[1], d[2]));
        }
        return new Pose(map);
    }
}
