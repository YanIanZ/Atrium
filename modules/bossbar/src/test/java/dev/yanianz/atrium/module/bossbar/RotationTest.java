package dev.yanianz.atrium.module.bossbar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.bossbar.BossBar;
import org.junit.jupiter.api.Test;

class RotationTest {
    @Test
    void cyclesInOrderWithGaps() {
        Rotation r = new Rotation(100, List.of(50L, 100L), false);
        Rotation.Active first = r.at(0);
        assertEquals(0, first.index());
        assertEquals(0.0, first.elapsed());
        assertEquals(0.5, r.at(25).elapsed(), 1e-9);
        assertNull(r.at(50), "first announcement ended; gap until the next slot");
        assertEquals(1, r.at(100).index());
        assertEquals(1, r.at(199).index());
        assertEquals(0, r.at(200).index());
        assertEquals(2, r.at(200).slot());
    }

    @Test
    void durationLongerThanIntervalIsCut() {
        Rotation r = new Rotation(20, List.of(1000L), false);
        assertEquals(0.5, r.at(10).elapsed(), 1e-9);
        assertNotNull(r.at(39));
        assertEquals(1, r.at(39).slot());
    }

    @Test
    void emptyRotationShowsNothing() {
        assertNull(new Rotation(20, List.of(), false).at(5));
    }

    @Test
    void randomOrderShowsEveryAnnouncementOncePerCycle() {
        Rotation r = new Rotation(10, List.of(10L, 10L, 10L, 10L, 10L), true);
        for (int cycle = 0; cycle < 6; cycle++) {
            Set<Integer> seen = new HashSet<>();
            for (int slot = 0; slot < 5; slot++) {
                seen.add(r.at((cycle * 5L + slot) * 10).index());
            }
            assertEquals(Set.of(0, 1, 2, 3, 4), seen);
        }
        assertEquals(r.at(70).index(), r.at(75).index(), "same slot, same announcement");
    }

    @Test
    void progressModes() {
        assertEquals(1f, Rotation.progress(true, 0.3, 0.0));
        assertEquals(0.25f, Rotation.progress(true, 0.3, 0.75), 1e-6);
        assertEquals(0.3f, Rotation.progress(false, 0.3, 0.75), 1e-6);
        assertEquals(0f, Rotation.progress(true, 1, 2.0));
    }

    @Test
    void settingsValidateColorsModesAndLimits() {
        BossbarConfig config = new BossbarConfig();
        BossbarConfig.Announcement bad = BossbarConfig.Announcement.of("x", 0, "rainbow", "sideways");
        bad.overlay = "NOTCHED_6";
        bad.progress = 4;
        config.announcements.add(bad);
        config.maxMessageSeconds = 0;
        List<String> problems = new ArrayList<>();
        BossbarSettings s = BossbarSettings.from(config, (o, p) -> problems.add(o));
        BossbarSettings.AnnouncementSpec spec = s.announcements().get(2);
        assertEquals(BossBar.Color.BLUE, spec.color());
        assertEquals(BossBar.Overlay.NOTCHED_6, spec.overlay());
        assertTrue(spec.countdown());
        assertEquals(1.0, spec.progress());
        assertEquals(1, s.maxMessageSeconds());
        assertEquals(List.of("announcements[2]", "announcements[2]", "announcements[2]",
                "announcements[2].color", "max-message-seconds"), problems);
    }

    @Test
    void timedBarCountsDownAndExpires() {
        BossBar bar = BossBar.bossBar(net.kyori.adventure.text.Component.text("x"), 1f, BossBar.Color.RED,
                BossBar.Overlay.PROGRESS);
        PlayerBars.TimedBar timed = new PlayerBars.TimedBar(bar, 1_000, 4_000);
        assertEquals(1f, timed.progress(1_000));
        assertEquals(0.5f, timed.progress(3_000), 1e-6);
        assertTrue(!timed.expired(4_999));
        assertTrue(timed.expired(5_000));
    }
}
