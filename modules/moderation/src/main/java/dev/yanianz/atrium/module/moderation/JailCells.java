package dev.yanianz.atrium.module.moderation;

import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.jspecify.annotations.Nullable;

/**
 * Spawn points inside a jail. A jail with no cells uses its own spawn point, so existing jails keep working.
 * The selection rules are pure so they can be tested without a server: cells are shuffled, then either a
 * random cell is taken or the least occupied one wins (an empty cell always beats an occupied one).
 */
final class JailCells {
    /** How new prisoners are spread over the cells of a jail. */
    enum Strategy {
        /** Shuffle the cells and pick the least occupied one; an empty cell always wins. */
        LEAST_OCCUPIED,
        /** Pick a random cell, regardless of who is already standing there. */
        SHUFFLE;

        static Strategy parse(@Nullable String raw) {
            if (raw == null) {
                return LEAST_OCCUPIED;
            }
            return switch (raw.strip().toUpperCase(Locale.ROOT)) {
                case "SHUFFLE" -> SHUFFLE;
                default -> LEAST_OCCUPIED;
            };
        }
    }

    /** One cell: a world name plus a standing position inside it. */
    record Cell(String world, double x, double y, double z, float yaw, float pitch) {}

    private JailCells() {}

    /**
     * Index of the cell a new prisoner is placed in, or {@code -1} when the jail has no cells.
     *
     * @param occupancy per-cell head count, in cell order; missing entries count as zero
     */
    static int pick(List<Cell> cells, int[] occupancy, Strategy strategy, Random random) {
        int size = cells.size();
        if (size == 0) {
            return -1;
        }
        int[] order = new int[size];
        for (int i = 0; i < size; i++) {
            order[i] = i;
        }
        // Fisher-Yates: every cell has the same chance, so a jail with several empty cells spreads players.
        for (int i = size - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int swap = order[i];
            order[i] = order[j];
            order[j] = swap;
        }
        if (strategy == Strategy.SHUFFLE) {
            return order[0];
        }
        int best = order[0];
        int bestCount = count(occupancy, best);
        for (int i = 1; i < size; i++) {
            int candidate = count(occupancy, order[i]);
            if (candidate < bestCount) {
                best = order[i];
                bestCount = candidate;
            }
        }
        return best;
    }

    private static int count(int[] occupancy, int index) {
        if (occupancy == null || index >= occupancy.length || occupancy[index] < 0) {
            return 0;
        }
        return occupancy[index];
    }
}
