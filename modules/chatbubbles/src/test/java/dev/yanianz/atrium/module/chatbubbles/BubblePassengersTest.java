package dev.yanianz.atrium.module.chatbubbles;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BubblePassengersTest {
    private static final int FAKE = -1_000_000_001;

    @Test
    void appendsTheFakeIdAfterRealPassengers() {
        assertArrayEquals(new int[] {FAKE}, BubblePassengers.compose(new int[0], FAKE));
        assertArrayEquals(new int[] {7, 9, FAKE}, BubblePassengers.compose(new int[] {7, 9}, FAKE));
    }

    @Test
    void keepsAnAlreadyPresentFakeIdWithoutDuplicatingIt() {
        assertArrayEquals(new int[] {7, FAKE}, BubblePassengers.compose(new int[] {7, FAKE}, FAKE));
        assertArrayEquals(new int[] {FAKE}, BubblePassengers.compose(new int[] {FAKE}, FAKE));
    }

    @Test
    void handlesMissingRealPassengers() {
        assertArrayEquals(new int[] {FAKE}, BubblePassengers.compose(null, FAKE));
    }

    @Test
    void containsReadsNullAsEmpty() {
        assertFalse(BubblePassengers.contains(null, FAKE));
        assertTrue(BubblePassengers.contains(new int[] {FAKE}, FAKE));
        assertFalse(BubblePassengers.contains(new int[] {1, 2}, FAKE));
    }
}
