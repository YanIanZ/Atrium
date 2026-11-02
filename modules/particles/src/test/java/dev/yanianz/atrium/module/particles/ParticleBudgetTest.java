package dev.yanianz.atrium.module.particles;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ParticleBudgetTest {
    @Test
    void bucketStartsFullAndConsumesWhatItHas() {
        ParticleBudget budget = new ParticleBudget(100, 2);
        assertEquals(100, budget.refill(2));
        assertEquals(40, budget.consume(40));
        assertEquals(60, budget.available());
        assertEquals(60, budget.consume(500));
        assertEquals(0, budget.available());
        assertEquals(0, budget.consume(10));
    }

    @Test
    void refillIsCappedAtTheConfiguredRate() {
        ParticleBudget budget = new ParticleBudget(100, 2);
        budget.consume(100);
        assertEquals(10, budget.refill(2));
        assertEquals(10, budget.available());
        assertEquals(100, budget.refill(200));
    }

    @Test
    void longIntervalEarnsMorePerRender() {
        ParticleBudget slow = new ParticleBudget(100, 10);
        slow.consume(100);
        assertEquals(50, slow.refill(10));
    }

    @Test
    void lowTpsHalvesDensityAndHighTpsKeepsIt() {
        assertEquals(0.5, ParticleBudget.throttle(12.0, 18.0, 0.5), 1.0e-9);
        assertEquals(1.0, ParticleBudget.throttle(19.9, 18.0, 0.5), 1.0e-9);
        assertEquals(1.0, ParticleBudget.throttle(Double.NaN, 18.0, 0.5), 1.0e-9);
        assertEquals(0.05, ParticleBudget.throttle(0.0, 18.0, 0.01), 1.0e-9);
    }

    @Test
    void countScalingRoundsDownAndNeverGoesNegative() {
        assertEquals(5, ParticleBudget.scale(10, 0.5));
        assertEquals(0, ParticleBudget.scale(10, 0.0));
        assertEquals(0, ParticleBudget.scale(-5, 0.5));
    }
}
