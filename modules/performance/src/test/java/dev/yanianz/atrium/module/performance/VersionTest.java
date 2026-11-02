package dev.yanianz.atrium.module.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VersionTest {
    @Test
    void parsesTheUsualTagShapes() {
        assertEquals(Version.parse("1.2.3"), Version.parse("v1.2.3"));
        assertEquals(Version.parse("1.2.3"), Version.parse(" V1.2.3 "));
        assertEquals(Version.parse("1.2.3"), Version.parse("1.2.3+build.7"));
        assertEquals("1.2.3-beta.1", Version.parse("v1.2.3-beta.1").toString());
    }

    @Test
    void comparesNumbersWithMissingPartsAsZero() {
        assertTrue(Version.parse("26.2").newerThan(Version.parse("26.1.9")));
        assertEquals(0, Version.parse("26.2").compareTo(Version.parse("26.2.0")));
        assertFalse(Version.parse("1.0").newerThan(Version.parse("1.0.0")));
        assertTrue(Version.parse("2").newerThan(Version.parse("1.99.99")));
    }

    @Test
    void aReleaseBeatsItsPrerelease() {
        Version release = Version.parse("1.4.0");
        Version candidate = Version.parse("1.4.0-rc.1");
        assertTrue(release.newerThan(candidate));
        assertFalse(candidate.newerThan(release));
        assertTrue(Version.parse("1.4.1").newerThan(release));
    }

    @Test
    void rejectsTextThatIsNotAVersion() {
        assertThrows(IllegalArgumentException.class, () -> Version.parse("latest"));
        assertThrows(IllegalArgumentException.class, () -> Version.parse(""));
        assertThrows(IllegalArgumentException.class, () -> Version.parse(null));
    }

    @Test
    void ignoresTrailingZeroesWhenComparingButRemembersThemWhenPrinting() {
        assertEquals("1.2.0", Version.parse("1.2.0").toString());
        assertEquals(0, Version.parse("1.2").compareTo(Version.parse("1.2.0")));
    }
}
