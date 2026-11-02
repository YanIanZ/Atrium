package dev.yanianz.atrium.module.effects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class NearbyTest {
    private static final Nearby.Point CENTRE = new Nearby.Point(10, 64, -4);

    @Test void aPlayerOnTheCentreCounts() {
        assertTrue(Nearby.anyWithin(CENTRE, 16, List.of(CENTRE)));
    }

    @Test void aPlayerInsideTheRadiusCounts() {
        assertTrue(Nearby.anyWithin(CENTRE, 16, List.of(new Nearby.Point(24, 70, -4))));
    }

    @Test void aPlayerOutsideTheRadiusDoesNotCount() {
        assertFalse(Nearby.anyWithin(CENTRE, 16, List.of(new Nearby.Point(30, 64, -4))));
    }

    @Test void heightIsIgnoredSoCavesStillActivateTheEffect() {
        assertTrue(Nearby.anyWithin(CENTRE, 8, List.of(new Nearby.Point(10, -20, -4))));
    }

    @Test void anySingleNearbyPlayerIsEnough() {
        assertTrue(Nearby.anyWithin(CENTRE, 8, List.of(
                new Nearby.Point(200, 64, 200),
                new Nearby.Point(12, 64, -3))));
    }

    @Test void emptyPlayerListNeverActivates() {
        assertFalse(Nearby.anyWithin(CENTRE, 32, List.of()));
    }

    @Test void negativeRadiusDisablesTheEffect() {
        assertFalse(Nearby.anyWithin(CENTRE, -5, List.of(CENTRE)));
    }
}
