package dev.yanianz.atrium.module.search;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ScanSpeedTest {
    @Test void floorIsAlwaysAtLeastOne() {
        assertEquals(1, ScanSpeed.floor(0));
        assertEquals(1, ScanSpeed.floor(-20));
        assertEquals(3, ScanSpeed.floor(3));
    }

    @Test void speedNeverStallsEvenWithAFullBacklog() {
        int speed = 5;
        for (int i = 0; i < 200; i++) {
            speed = ScanSpeed.next(speed, 10_000, 0, 8);
            assertTrue(speed >= 1, "speed " + speed);
        }
        assertEquals(1, speed);
        assertEquals(1, ScanSpeed.next(-50, 7, -3, 8));
    }

    @Test void speedRampsUpWhenIdleAndIsClampedToTheMaximum() {
        int speed = 1;
        for (int i = 0; i < 50; i++) {
            speed = ScanSpeed.next(speed, 0, 1, 8);
        }
        assertEquals(8, speed);
        assertEquals(3, ScanSpeed.next(10, 0, 3, 1), "a maximum below the minimum collapses to the minimum");
    }

    @Test void dispatchIsBoundedByTheBudgetAndBacklog() {
        assertEquals(4, ScanSpeed.dispatch(4, 0));
        assertEquals(1, ScanSpeed.dispatch(4, 3));
        assertEquals(0, ScanSpeed.dispatch(4, 9));
        assertEquals(1, ScanSpeed.dispatch(0, 0), "a zero budget still dispatches one chunk");
        assertEquals(1, ScanSpeed.dispatch(-5, -1));
    }
}
