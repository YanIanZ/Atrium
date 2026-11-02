package dev.yanianz.atrium.module.particles;

/**
 * Token bucket that caps how many particles one player may spawn per second. Pure math: the renderer
 * feeds it the elapsed ticks and consumes the points it is about to draw.
 */
public final class ParticleBudget {
    private final double capacity;
    private double tokens;

    /**
     * @param particlesPerSecond configured ceiling
     * @param intervalTicks      render period; one second contains {@code 20 / intervalTicks} renders
     */
    public ParticleBudget(int particlesPerSecond, int intervalTicks) {
        this.capacity = Math.max(1, particlesPerSecond);
        // Start full so the first render is never starved.
        this.tokens = capacity;
    }

    /** Adds the particles earned since the previous render and returns the whole points available. */
    public int refill(int intervalTicks) {
        tokens = Math.min(capacity, tokens + capacity * Math.max(1, intervalTicks) / 20.0);
        return available();
    }

    public int available() {
        return (int) Math.floor(tokens);
    }

    /** Consumes up to {@code requested} points and returns how many were granted. */
    public int consume(int requested) {
        if (requested <= 0) {
            return 0;
        }
        int granted = Math.min(requested, available());
        tokens -= granted;
        return granted;
    }

    /** Density multiplier: halves the shape when the region TPS is below the threshold. */
    public static double throttle(double tps, double threshold, double multiplier) {
        if (!Double.isFinite(tps) || !Double.isFinite(threshold)) {
            return 1.0;
        }
        return tps < threshold ? Math.max(0.05, Math.min(1.0, multiplier)) : 1.0;
    }

    public static int scale(int points, double multiplier) {
        if (points <= 0) {
            return 0;
        }
        return Math.max(0, (int) Math.round(points * Math.max(0.0, multiplier)));
    }
}
