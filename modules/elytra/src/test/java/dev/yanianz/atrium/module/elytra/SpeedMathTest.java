package dev.yanianz.atrium.module.elytra;

import static org.junit.jupiter.api.Assertions.*;

import dev.yanianz.atrium.module.elytra.SpeedMath.Vec;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SpeedMathTest {
    private static final double EPS = 1e-9;

    @Test void clampKeepsDirectionAndNeverExceedsTheCap() {
        Vec v = SpeedMath.clamp(new Vec(3, 4, 0), 2.5);
        assertEquals(2.5, v.length(), EPS);
        assertEquals(0.6, v.x() / v.length(), EPS);
        assertEquals(new Vec(1, 1, 0), SpeedMath.clamp(new Vec(1, 1, 0), 2.5), "below the cap is unchanged");
        assertEquals(0.0, SpeedMath.clamp(new Vec(1, 0, 0), -3).length(), EPS);
    }

    @Test void boostMultipliesAndIsCapped() {
        Vec current = new Vec(1, -0.2, 0);
        Vec out = SpeedMath.boost(current, current.length(), new Vec(1, 0, 0), 1.5, 0.8, 2.5).orElseThrow();
        assertEquals(1.5, out.x(), EPS);
        assertEquals(-0.3, out.y(), EPS);
        Vec fast = new Vec(2, 0, 0);
        Vec capped = SpeedMath.boost(fast, 2, new Vec(1, 0, 0), 3, 0.8, 2.5).orElseThrow();
        assertEquals(2.5, capped.length(), EPS);
    }

    @Test void noBoostAtOrAboveTheCapEvenIfTheReportedVelocityIsLow() {
        // The reported velocity is tiny, but the measured movement is at the cap.
        Vec reported = new Vec(0.1, 0, 0);
        Optional<Vec> measured = Optional.of(new Vec(2.6, 0, 0));
        double speed = SpeedMath.effectiveSpeed(reported, measured);
        assertEquals(2.6, speed, EPS);
        assertTrue(SpeedMath.boost(reported, speed, new Vec(1, 0, 0), 1.5, 0.8, 2.5).isEmpty());
        assertTrue(SpeedMath.boost(new Vec(2.5, 0, 0), 2.5, new Vec(1, 0, 0), 1.5, 0.8, 2.5).isEmpty());
    }

    @Test void slowPlayersArePushedAlongTheLookDirection() {
        Vec out = SpeedMath.boost(new Vec(0.1, 0, 0), 0.1, new Vec(0, 0, 2), 1.5, 0.8, 2.5).orElseThrow();
        assertEquals(new Vec(0, 0, 0.8), out);
        assertTrue(SpeedMath.boost(Vec.ZERO, 0, Vec.ZERO, 1.5, 0.8, 2.5).isEmpty());
        // A multiplier below 1 never slows the player down.
        Vec kept = SpeedMath.boost(new Vec(1, 0, 0), 1, new Vec(1, 0, 0), 0.2, 0.5, 2.5).orElseThrow();
        assertEquals(1.0, kept.length(), EPS);
    }

    @Test void measuredVelocityIsPerTickAndRejectsStaleSamples() {
        Vec from = new Vec(0, 64, 0);
        Vec to = new Vec(3, 64, 4);
        assertEquals(5.0, SpeedMath.measured(from, to, 50_000_000L, 10).orElseThrow().length(), EPS);
        assertEquals(2.5, SpeedMath.measured(from, to, 100_000_000L, 10).orElseThrow().length(), EPS);
        assertEquals(5.0, SpeedMath.measured(from, to, 1_000_000L, 10).orElseThrow().length(), EPS, "below one tick counts as one");
        assertTrue(SpeedMath.measured(from, to, 2_000_000_000L, 10).isEmpty());
        assertTrue(SpeedMath.measured(from, to, -1, 10).isEmpty());
        assertEquals(0.3, SpeedMath.effectiveSpeed(new Vec(0.3, 0, 0), Optional.empty()), EPS);
    }

    @Test void launchGoesUpAndForwardWithinTheCap() {
        Vec v = SpeedMath.launch(new Vec(0, -1, 1), 0.5, 1.4, 2.5);
        assertEquals(0.0, v.x(), EPS);
        assertEquals(1.4, v.y(), EPS);
        assertEquals(0.5, v.z(), EPS);
        Vec straightDown = SpeedMath.launch(new Vec(0, -1, 0), 0.5, 1.4, 2.5);
        assertEquals(new Vec(0, 1.4, 0), straightDown);
        assertTrue(SpeedMath.launch(new Vec(1, 0, 0), 4, 4, 2.5).length() <= 2.5 + EPS);
    }

    @Test void nonFiniteInputBecomesZero() {
        assertEquals(Vec.ZERO, new Vec(Double.NaN, 1, 1));
        assertEquals(Vec.ZERO, new Vec(Double.POSITIVE_INFINITY, 1, 1));
    }
}
