package dev.yanianz.atrium.module.warps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Deck of location indices for a warp with extra random locations.
 *
 * <p>{@code NON_REPEATING} shuffles the whole pool and hands out every location once; only when the deck is
 * empty is it reshuffled, so a player never lands on the same spot twice in a row. {@code REPEATING} picks a
 * uniformly random location every time and never stores progress. A change of the pool size (a location added
 * or removed) reshuffles immediately, so a stale index can never point outside the pool.
 *
 * <p>{@link State} is immutable, so a pick can be computed without locking: the caller stores the returned
 * state atomically.
 */
final class RandomDeck {
    /** Shuffled indices and how many were already handed out. */
    record State(List<Integer> order, int used) {
        State {
            order = List.copyOf(order);
            used = Math.max(0, used);
        }

        static final State EMPTY = new State(List.of(), 0);
    }

    /** The chosen index and the state to store before the next pick. */
    record Pick(State state, int index) {}

    private RandomDeck() {}

    static Pick next(State state, int size) {
        return next(state, size, false, ThreadLocalRandom.current());
    }

    /**
     * @param state     the previous state, or {@link State#EMPTY}
     * @param size       number of locations; must be at least 1
     * @param repeating pick uniformly at random instead of walking a shuffled deck
     * @param random    source of randomness (tests pass a seeded instance)
     */
    static Pick next(State state, int size, boolean repeating, Random random) {
        if (size < 1) {
            throw new IllegalArgumentException("a deck needs at least one location");
        }
        if (state == null) {
            state = State.EMPTY;
        }
        if (repeating) {
            return new Pick(state, random.nextInt(size));
        }
        if (state.order().size() != size || state.used() >= state.order().size()) {
            List<Integer> order = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                order.add(i);
            }
            Collections.shuffle(order, random);
            state = new State(order, 0);
        }
        int index = state.order().get(state.used());
        return new Pick(new State(state.order(), state.used() + 1), index);
    }
}
