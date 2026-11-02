package dev.yanianz.atrium.module.seating;

import org.jspecify.annotations.Nullable;

/**
 * Rules for {@code /ride}: who may ride what, and how strong a launch may be. Pure, so the limits are unit
 * tested without a server.
 */
final class RideRules {
    enum Verdict {
        OK, PLAYERS_DISABLED, FULL, UNSUITABLE, SELF
    }

    /** Hard cap on the launch power, in blocks per tick of vertical velocity. */
    static final double MAX_LAUNCH = 2.5;
    static final double MIN_LAUNCH = 0.2;

    private RideRules() {}

    /**
     * @param targetIsPlayer   true when the vehicle is another player
     * @param playersAllowed   the config's ride.players switch
     * @param passengers       how many passengers the vehicle already carries
     * @param maxPassengers    configured passenger cap
     * @param suitable         entity type that can carry a passenger (living, not a vehicle itself)
     * @param sameEntity       true when the rider tried to ride themselves
     */
    static Verdict check(boolean targetIsPlayer, boolean playersAllowed, int passengers, int maxPassengers,
            boolean suitable, boolean sameEntity) {
        if (sameEntity) {
            return Verdict.SELF;
        }
        if (targetIsPlayer && !playersAllowed) {
            return Verdict.PLAYERS_DISABLED;
        }
        if (!suitable) {
            return Verdict.UNSUITABLE;
        }
        if (passengers >= Math.max(1, maxPassengers)) {
            return Verdict.FULL;
        }
        return Verdict.OK;
    }

    /** Clamps a requested launch power into {@code [MIN_LAUNCH, MAX_LAUNCH]}. */
    static double launchPower(double requested) {
        if (Double.isNaN(requested)) {
            return MIN_LAUNCH;
        }
        // Infinity is clamped like any other too-large value instead of becoming the minimum.
        return Math.max(MIN_LAUNCH, Math.min(MAX_LAUNCH, requested));
    }

    /** Parses an optional launch power; returns {@code null} when the text is not a number. */
    static @Nullable Double parsePower(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(text.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * A dismount spot is safe when the player's feet and head are free. Used to decide whether the player
     * has to be moved up before leaving the vehicle.
     */
    static boolean needsRescue(boolean feetFree, boolean headFree, boolean onSolidGround) {
        return !(feetFree && headFree && onSolidGround);
    }
}
