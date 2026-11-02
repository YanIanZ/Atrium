package dev.yanianz.atrium.module.performance;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class TpsSummaryTest {
    @Test void summarisesFinitePositiveValues() {
        TpsSummary summary = TpsSummary.of(List.of(20.0, 19.0, 18.0)).orElseThrow();
        assertEquals(3, summary.regions());
        assertEquals(18.0, summary.min());
        assertEquals(19.0, summary.average(), 1e-9);
        assertEquals(20.0, summary.max());
    }

    @Test void valuesAreClampedAndInvalidSamplesSkipped() {
        TpsSummary summary = TpsSummary.of(java.util.Arrays.asList(25.0, -1.0, null, Double.NaN, 10.0)).orElseThrow();
        assertEquals(3, summary.regions());
        assertEquals(0.0, summary.min(), 1e-9);
        assertEquals(20.0, summary.max());
        assertEquals((20.0 + 0.0 + 10.0) / 3.0, summary.average(), 1e-9);
    }

    @Test void emptySamplesHaveNoSummary() {
        assertTrue(TpsSummary.of(List.of()).isEmpty());
        assertTrue(TpsSummary.of(java.util.Arrays.asList((Double) null, Double.NaN)).isEmpty());
    }
}
