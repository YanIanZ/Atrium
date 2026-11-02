package dev.yanianz.atrium.module.staff;

/**
 * Persisted spectator session. The original location and game mode are captured once, when the first
 * {@code /spectate <player>} starts; switching to another target keeps the same return point.
 */
public record SpectateState(
        boolean active,
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        String gameMode,
        String target) {

    public static final SpectateState EMPTY =
            new SpectateState(false, "", 0, 0, 0, 0, 0, "SURVIVAL", "");

    public SpectateState {
        world = world == null ? "" : world;
        gameMode = gameMode == null ? "SURVIVAL" : gameMode;
        target = target == null ? "" : target;
    }

    public static SpectateState started(String world, double x, double y, double z, float yaw, float pitch,
            String gameMode, String target) {
        return new SpectateState(true, world, x, y, z, yaw, pitch, gameMode, target);
    }

    /** Same return point, new target. A state that is not active stays unchanged. */
    public SpectateState retarget(String newTarget) {
        if (!active) {
            return this;
        }
        return new SpectateState(true, world, x, y, z, yaw, pitch, gameMode, newTarget);
    }

    public SpectateState cleared() {
        return EMPTY;
    }
}
