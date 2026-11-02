package dev.yanianz.atrium.module.afk;

/**
 * Physical state that can move a player without them choosing to move.
 *
 * <p>{@code activeInput} is true when the player is pressing a movement key. It lets the module keep
 * swimming and boating as real activity while ignoring water current, vehicle drift and piston pushes.
 */
record MovementFlags(boolean inVehicle, boolean inWater, boolean inLava, boolean activeInput) {
    static final MovementFlags NONE = new MovementFlags(false, false, false, false);

    boolean pushed() {
        return inVehicle || inWater || inLava;
    }
}
