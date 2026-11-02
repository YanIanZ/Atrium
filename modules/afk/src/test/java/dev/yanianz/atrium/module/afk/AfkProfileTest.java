package dev.yanianz.atrium.module.afk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AfkProfileTest {
    @Test
    void addsSessionsAndSeconds() {
        AfkProfile profile = new AfkProfile(10, 2).plus(5);
        assertEquals(15, profile.totalSeconds());
        assertEquals(3, profile.sessions());
    }

    @Test
    void ignoresNonPositiveDurations() {
        AfkProfile profile = new AfkProfile(10, 2);
        assertEquals(profile, profile.plus(0));
        assertEquals(profile, profile.plus(-1));
    }

    @Test
    void saturatesInsteadOfWrapping() {
        AfkProfile profile = new AfkProfile(Long.MAX_VALUE, Long.MAX_VALUE).plus(1);
        assertEquals(Long.MAX_VALUE, profile.totalSeconds());
        assertEquals(Long.MAX_VALUE, profile.sessions());
    }

    @Test
    void rejectsNegativeTotals() {
        assertThrows(IllegalArgumentException.class, () -> new AfkProfile(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new AfkProfile(0, -1));
    }
}
