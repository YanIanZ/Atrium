package dev.yanianz.atrium.module.vanish;

/**
 * Persisted vanish flags of one player.
 *
 * @param vanished whether the player is vanished (restored on rejoin)
 * @param pickup   whether the player picks up items while vanished ({@code /vanish pickup})
 */
public record VanishState(boolean vanished, boolean pickup) {
    public static final VanishState DEFAULT = new VanishState(false, false);

    public VanishState withVanished(boolean value) {
        return new VanishState(value, pickup);
    }

    public VanishState withPickup(boolean value) {
        return new VanishState(vanished, value);
    }
}
