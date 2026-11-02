package dev.yanianz.atrium.module.chatbubbles;

import java.util.Arrays;

/**
 * Builds the passenger list of a speaker. The bubble is a client-side passenger of the player, so the
 * mount packet must contain the player's real passengers followed by the fake entity id. Real passengers
 * are read on the speaker's own thread and passed in here.
 */
final class BubblePassengers {
    private BubblePassengers() {}

    static int[] compose(int[] realPassengers, int fakeId) {
        int[] real = realPassengers == null ? new int[0] : realPassengers;
        if (contains(real, fakeId)) {
            return real.clone();
        }
        int[] result = Arrays.copyOf(real, real.length + 1);
        result[real.length] = fakeId;
        return result;
    }

    static boolean contains(int[] ids, int id) {
        if (ids == null) {
            return false;
        }
        for (int candidate : ids) {
            if (candidate == id) {
                return true;
            }
        }
        return false;
    }
}
