package dev.yanianz.atrium.module.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SpeedSamplesTest {
    private static SpeedSamples.Sample at(long time, double x) {
        return new SpeedSamples.Sample(time, x, 64, 0);
    }

    @Test
    void straightLineSpeed() {
        // One block in 100 ms = 10 blocks per second = 36 km/h.
        List<SpeedSamples.Sample> window = SpeedSamples.add(List.of(), at(0, 0));
        window = SpeedSamples.add(window, at(100, 1));
        assertEquals(36.0, SpeedSamples.kmh(window), 0.001);
    }

    @Test
    void aSingleSampleReportsNothing() {
        assertEquals(0.0, SpeedSamples.kmh(List.of(at(0, 0))));
        assertEquals(0.0, SpeedSamples.kmh(List.of()));
    }

    @Test
    void oldSamplesLeaveTheWindow() {
        List<SpeedSamples.Sample> window = SpeedSamples.add(List.of(), at(0, 0));
        window = SpeedSamples.add(window, at(100, 1));
        window = SpeedSamples.add(window, at(550, 2));
        assertEquals(2, window.size(), "the 0 ms sample is older than the window and was dropped");
        assertEquals(100L, window.get(0).time());
        window = SpeedSamples.add(window, at(700, 3));
        assertEquals(2, window.size(), "only the 550 ms sample is still inside the window");
        assertEquals(550L, window.get(0).time());
    }

    @Test
    void aJumpClearsTheWindow() {
        List<SpeedSamples.Sample> window = SpeedSamples.add(List.of(), at(0, 0));
        window = SpeedSamples.add(window, at(100, 1000));
        assertEquals(1, window.size());
        assertEquals(0.0, SpeedSamples.kmh(window));
    }

    @Test
    void aJumpExactlyAtTheLimitIsKept() {
        List<SpeedSamples.Sample> window = SpeedSamples.add(List.of(), at(0, 0));
        window = SpeedSamples.add(window, at(100, SpeedSamples.MAX_JUMP));
        assertEquals(2, window.size());
    }

    @Test
    void outOfOrderSamplesRestartTheWindow() {
        List<SpeedSamples.Sample> window = SpeedSamples.add(List.of(), at(500, 0));
        window = SpeedSamples.add(window, at(400, 1));
        assertEquals(1, window.size());
        assertEquals(400L, window.get(0).time());
    }

    @Test
    void verticalMovementCounts() {
        List<SpeedSamples.Sample> window = SpeedSamples.add(List.of(),
                new SpeedSamples.Sample(0, 0, 0, 0));
        window = SpeedSamples.add(window, new SpeedSamples.Sample(500, 0, 10, 0));
        assertEquals(72.0, SpeedSamples.kmh(window), 0.001);
    }

    @Test
    void formatIsLocaleSafeAndNeverNegative() {
        assertEquals("12.3", SpeedSamples.format(12.34));
        assertEquals("0.0", SpeedSamples.format(-5));
        assertTrue(SpeedSamples.format(0).matches("0\\.0"));
    }
}
