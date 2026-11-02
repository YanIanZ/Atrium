package dev.yanianz.atrium.module.mobs;

import org.jspecify.annotations.Nullable;

/**
 * Editable values of a spawner. Pure so the clamping rules of the editor are unit tested; the menu converts
 * between this record and a {@code CreatureSpawner} on the block's own region thread.
 */
record SpawnerSettings(
        int delay,
        int minDelay,
        int maxDelay,
        int spawnCount,
        int playerRange,
        int spawnRange,
        int maxNearby,
        @Nullable String typeKey) {

    static final int MIN_DELAY = 1;
    static final int MAX_DELAY = 1200;
    static final int MAX_COUNT = 32;
    static final int MAX_RANGE = 32;
    static final int MAX_NEARBY = 200;

    enum Field { DELAY, MIN_DELAY, MAX_DELAY, SPAWN_COUNT, PLAYER_RANGE, SPAWN_RANGE, MAX_NEARBY }

    SpawnerSettings {
        delay = Math.clamp(delay, 0, MAX_DELAY);
        minDelay = Math.clamp(minDelay, MIN_DELAY, MAX_DELAY);
        maxDelay = Math.clamp(maxDelay, minDelay, MAX_DELAY);
        spawnCount = Math.clamp(spawnCount, 1, MAX_COUNT);
        playerRange = Math.clamp(playerRange, 1, MAX_RANGE);
        spawnRange = Math.clamp(spawnRange, 1, MAX_RANGE);
        maxNearby = Math.clamp(maxNearby, 1, MAX_NEARBY);
    }

    /** A copy with {@code field} moved by {@code delta}, clamped to its own range. */
    SpawnerSettings shift(Field field, int delta) {
        return switch (field) {
            case DELAY -> new SpawnerSettings(delay + delta, minDelay, maxDelay, spawnCount, playerRange,
                    spawnRange, maxNearby, typeKey);
            case MIN_DELAY -> new SpawnerSettings(delay, Math.min(minDelay + delta, maxDelay), maxDelay, spawnCount,
                    playerRange, spawnRange, maxNearby, typeKey);
            case MAX_DELAY -> new SpawnerSettings(delay, minDelay, Math.max(maxDelay + delta, minDelay), spawnCount,
                    playerRange, spawnRange, maxNearby, typeKey);
            case SPAWN_COUNT -> new SpawnerSettings(delay, minDelay, maxDelay, spawnCount + delta, playerRange,
                    spawnRange, maxNearby, typeKey);
            case PLAYER_RANGE -> new SpawnerSettings(delay, minDelay, maxDelay, spawnCount, playerRange + delta,
                    spawnRange, maxNearby, typeKey);
            case SPAWN_RANGE -> new SpawnerSettings(delay, minDelay, maxDelay, spawnCount, playerRange,
                    spawnRange + delta, maxNearby, typeKey);
            case MAX_NEARBY -> new SpawnerSettings(delay, minDelay, maxDelay, spawnCount, playerRange, spawnRange,
                    maxNearby + delta, typeKey);
        };
    }

    SpawnerSettings withType(@Nullable String newTypeKey) {
        return new SpawnerSettings(delay, minDelay, maxDelay, spawnCount, playerRange, spawnRange, maxNearby,
                newTypeKey);
    }
}
