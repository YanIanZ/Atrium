package dev.yanianz.atrium.module.warps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RandomDeckTest {
    @Test
    void nonRepeatingHandsOutEveryIndexExactlyOnce() {
        RandomDeck.State state = RandomDeck.State.EMPTY;
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            RandomDeck.Pick pick = RandomDeck.next(state, 5, false, new Random(42));
            state = pick.state();
            assertTrue(seen.add(pick.index()), "an index was handed out twice: " + pick.index());
        }
        assertEquals(Set.of(0, 1, 2, 3, 4), seen);
    }

    @Test
    void nonRepeatingReshufflesOnlyWhenTheDeckIsEmpty() {
        Random random = new Random(7);
        RandomDeck.State state = RandomDeck.State.EMPTY;
        for (int i = 0; i < 3; i++) {
            state = RandomDeck.next(state, 3, false, random).state();
        }
        assertEquals(3, state.used());
        RandomDeck.Pick pick = RandomDeck.next(state, 3, false, random);
        assertEquals(1, pick.state().used(), "a full deck restarts a new shuffle");
        assertTrue(pick.index() >= 0 && pick.index() < 3);
    }

    @Test
    void repeatingNeverKeepsProgressAndStaysInRange() {
        Random random = new Random(3);
        RandomDeck.State state = RandomDeck.State.EMPTY;
        for (int i = 0; i < 50; i++) {
            RandomDeck.Pick pick = RandomDeck.next(state, 4, true, random);
            state = pick.state();
            assertTrue(pick.index() >= 0 && pick.index() < 4);
            assertEquals(RandomDeck.State.EMPTY, state);
        }
    }

    @Test
    void aChangedPoolSizeReshuffles() {
        RandomDeck.State grown = RandomDeck.next(RandomDeck.State.EMPTY, 3, false, new Random(1)).state();
        RandomDeck.Pick pick = RandomDeck.next(grown, 5, false, new Random(1));
        assertEquals(1, pick.state().used());
        assertEquals(5, pick.state().order().size());
        assertTrue(pick.index() < 5);

        RandomDeck.State shrunk = RandomDeck.next(RandomDeck.State.EMPTY, 5, false, new Random(1)).state();
        RandomDeck.Pick smaller = RandomDeck.next(shrunk, 2, false, new Random(1));
        assertEquals(2, smaller.state().order().size());
        assertTrue(smaller.index() < 2);
    }

    @Test
    void singleLocationAlwaysReturnsZero() {
        RandomDeck.State state = RandomDeck.State.EMPTY;
        for (int i = 0; i < 3; i++) {
            RandomDeck.Pick pick = RandomDeck.next(state, 1, false, new Random(i));
            assertEquals(0, pick.index());
            state = pick.state();
        }
    }

    @Test
    void nullStateAndEmptyPoolAreHandled() {
        assertEquals(RandomDeck.State.EMPTY, RandomDeck.State.EMPTY);
        RandomDeck.Pick pick = RandomDeck.next(null, 2, false, new Random(1));
        assertTrue(pick.index() >= 0 && pick.index() < 2);
        assertThrows(IllegalArgumentException.class, () -> RandomDeck.next(null, 0, false, new Random(1)));
    }

    @Test
    void negativeUsedIsNormalised() {
        assertEquals(0, new RandomDeck.State(List.of(0, 1), -4).used());
    }

    @Test
    void stateIsImmutable() {
        RandomDeck.State original = RandomDeck.next(RandomDeck.State.EMPTY, 3, false, new Random(1)).state();
        RandomDeck.State copy = new RandomDeck.State(original.order(), original.used());
        assertEquals(original, copy);
        assertTrue(original.order().containsAll(Set.of(0, 1, 2)));
    }
}
