package dev.yanianz.atrium.module.elytra;

import java.util.Optional;

/**
 * Pure velocity math for the boost and the launch. Speeds are in blocks per tick (20 ticks = 1 second).
 * Results are always finite and never longer than the configured maximum.
 */
final class SpeedMath {
    /** Nanoseconds per server tick at 20 TPS. */
    static final double NANOS_PER_TICK = 50_000_000.0;

    private SpeedMath() {}

    record Vec(double x, double y, double z) {
        static final Vec ZERO = new Vec(0, 0, 0);

        Vec {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                x = 0;
                y = 0;
                z = 0;
            }
        }

        double length() {
            return Math.sqrt(x * x + y * y + z * z);
        }

        Vec scale(double factor) {
            return new Vec(x * factor, y * factor, z * factor);
        }

        Vec plus(Vec o) {
            return new Vec(x + o.x, y + o.y, z + o.z);
        }

        /** Unit vector, or {@link #ZERO} for a (near) zero vector. */
        Vec normalize() {
            double length = length();
            return length < 1e-9 ? ZERO : scale(1.0 / length);
        }
    }

    /** Shortens the vector to {@code max} when it is longer; the direction is kept. */
    static Vec clamp(Vec v, double max) {
        double limit = Math.max(0.0, max);
        double length = v.length();
        return length > limit && length > 0 ? v.scale(limit / length) : v;
    }

    /**
     * Velocity in blocks per tick measured from two positions. Empty when the samples are too far apart in
     * time ({@code > maxAgeTicks}) to describe the current motion. Elapsed time below one tick counts as one tick.
     */
    static Optional<Vec> measured(Vec from, Vec to, long elapsedNanos, double maxAgeTicks) {
        if (elapsedNanos < 0) {
            return Optional.empty();
        }
        double ticks = Math.max(1.0, elapsedNanos / NANOS_PER_TICK);
        if (ticks > maxAgeTicks) {
            return Optional.empty();
        }
        return Optional.of(new Vec((to.x - from.x) / ticks, (to.y - from.y) / ticks, (to.z - from.z) / ticks));
    }

    /**
     * The speed used for the limit: the larger of what the server reports and what the movement samples
     * show. Using the maximum means a client that hides its speed in one of the two values still hits the cap.
     */
    static double effectiveSpeed(Vec server, Optional<Vec> measured) {
        return Math.max(server.length(), measured.map(Vec::length).orElse(0.0));
    }

    /**
     * Sneak boost while gliding: multiply the current velocity, push along the look direction when the
     * result is slower than {@code minSpeed}, and cap at {@code maxSpeed}. Empty when the player is already
     * at or above the cap ({@code currentSpeed}), so the boost never slows anyone down.
     */
    static Optional<Vec> boost(Vec current, double currentSpeed, Vec look, double multiplier, double minSpeed, double maxSpeed) {
        if (currentSpeed >= maxSpeed || maxSpeed <= 0) {
            return Optional.empty();
        }
        Vec boosted = current.scale(Math.max(1.0, multiplier));
        if (boosted.length() < minSpeed) {
            Vec direction = look.normalize();
            if (direction.equals(Vec.ZERO)) {
                direction = current.normalize();
            }
            if (direction.equals(Vec.ZERO)) {
                return Optional.empty();
            }
            boosted = direction.scale(minSpeed);
        }
        return Optional.of(clamp(boosted, maxSpeed));
    }

    /** Launch velocity: up by {@code upward} plus {@code forward} along the horizontal look direction, capped. */
    static Vec launch(Vec look, double forward, double upward, double maxSpeed) {
        Vec horizontal = new Vec(look.x, 0, look.z).normalize().scale(Math.max(0.0, forward));
        return clamp(horizontal.plus(new Vec(0, Math.max(0.0, upward), 0)), maxSpeed);
    }
}
