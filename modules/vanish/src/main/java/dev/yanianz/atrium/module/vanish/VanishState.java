package dev.yanianz.atrium.module.vanish;

/**
 * Persisted vanish flags of one player.
 *
 * @param vanished    whether the player is vanished (restored on rejoin)
 * @param pickup      whether the player picks up items while vanished ({@code /vanish pickup})
 * @param silentChest whether containers open silently even while the player is visible ({@code /silentchest})
 */
public record VanishState(boolean vanished, boolean pickup, boolean silentChest) {
    public static final VanishState DEFAULT = new VanishState(false, false, false);

    public VanishState withVanished(boolean value) {
        return new VanishState(value, pickup, silentChest);
    }

    public VanishState withPickup(boolean value) {
        return new VanishState(vanished, value, silentChest);
    }

    public VanishState withSilentChest(boolean value) {
        return new VanishState(vanished, pickup, value);
    }
}
