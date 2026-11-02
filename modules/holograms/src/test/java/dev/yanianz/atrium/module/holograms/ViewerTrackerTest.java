package dev.yanianz.atrium.module.holograms;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ViewerTrackerTest {
    @Test void startAllWithNobodyOnlineStillTracksLaterJoins() {
        // Boot with an empty server: no per-player start happens, yet joins must not be ignored.
        ViewerTracker tracker = new ViewerTracker(null, null, null);
        assertFalse(tracker.running());
        tracker.startAll(List.of());
        assertTrue(tracker.running());
    }

    @Test void stopDisablesTrackingUntilStartedAgain() {
        ViewerTracker tracker = new ViewerTracker(null, null, null);
        tracker.startAll(List.of());
        tracker.stop();
        assertFalse(tracker.running());
        tracker.startAll(List.of());
        assertTrue(tracker.running());
    }
}
