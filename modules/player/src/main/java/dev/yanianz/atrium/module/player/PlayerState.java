package dev.yanianz.atrium.module.player;

/** Persisted player flags. Updates preserve the other flags. */
public record PlayerState(boolean flying, boolean god) {
    public static final PlayerState DEFAULT = new PlayerState(false, false);

    public PlayerState withFlying(boolean value) {
        return new PlayerState(value, god);
    }

    public PlayerState withGod(boolean value) {
        return new PlayerState(flying, value);
    }
}
