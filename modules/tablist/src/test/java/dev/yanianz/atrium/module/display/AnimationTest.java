package dev.yanianz.atrium.module.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnimationTest {
    @Test
    void uniformIntervalCyclesThroughFrames() {
        Animation a = Animation.of("t", List.of("A", "B", "C"), 10, List.of());
        assertEquals(30, a.period());
        assertEquals("A", a.frameAt(0));
        assertEquals("A", a.frameAt(9));
        assertEquals("B", a.frameAt(10));
        assertEquals("C", a.frameAt(29));
        assertEquals("A", a.frameAt(30));
        assertEquals("B", a.frameAt(10 + 30 * 1000L));
    }

    @Test
    void perFrameDurations() {
        Animation a = Animation.of("t", List.of("long", "short", "mid"), 99, List.of(40, 5, 15));
        assertEquals(60, a.period());
        assertEquals("long", a.frameAt(0));
        assertEquals("long", a.frameAt(39));
        assertEquals("short", a.frameAt(40));
        assertEquals("short", a.frameAt(44));
        assertEquals("mid", a.frameAt(45));
        assertEquals("mid", a.frameAt(59));
        assertEquals("long", a.frameAt(60));
    }

    @Test
    void mismatchedOrInvalidFrameTicksFallBackToInterval() {
        Animation a = Animation.of("t", List.of("A", "B"), 7, List.of(3));
        assertEquals(List.of(7, 7), a.durations());
        Animation b = Animation.of("t", List.of("A", "B"), 0, List.of(-1, 4));
        assertEquals(List.of(1, 4), b.durations());
    }

    @Test
    void negativeTicksStillLoop() {
        Animation a = Animation.of("t", List.of("A", "B"), 10, List.of());
        assertEquals("B", a.frameAt(-1));
        assertEquals("A", a.frameAt(-20));
    }

    @Test
    void invalidAnimationsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Animation("x", List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Animation("x", List.of("a"), List.of(0)));
    }

    @Test
    void animationsFromConfigReportProblemsAndLowercaseNames() {
        Map<String, AnimationConfig> config = new LinkedHashMap<>();
        config.put("Title", AnimationConfig.of(5, List.of(), "x", "y"));
        config.put("bad name", AnimationConfig.of(5, List.of(), "x"));
        config.put("empty", new AnimationConfig());
        List<String> problems = new ArrayList<>();
        Animations animations = Animations.from(config, (n, p) -> problems.add(n));
        assertEquals(Map.of("title", "y"), animations.framesAt(5));
        assertEquals(List.of("bad name", "empty"), problems);
    }

    @Test
    void substituteIsSinglePassAndNeverRecurses() {
        Map<String, String> frames = Map.of("a", "<anim:a>!", "b", "$1\\x");
        assertEquals("[<anim:a>!]", Animations.substitute("[<anim:a>]", frames));
        assertEquals("$1\\x and ", Animations.substitute("<anim:B> and <anim:missing>", frames));
        assertEquals("plain", Animations.substitute("plain", frames));
        assertTrue(Animations.substitute(null, frames).isEmpty());
    }
}
