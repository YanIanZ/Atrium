package dev.yanianz.atrium.module.player;

import java.util.Set;
import org.bukkit.GameMode;

/** Pure decision of whether a player may fly, independent of Bukkit state. */
final class FlightPolicy {
    private FlightPolicy() {}

    /**
     * Flight is allowed while at least one owner claims it. Creative and spectator modes allow it by
     * themselves, so leaving those modes with an active claim keeps flight on.
     */
    static boolean allowFlight(Set<String> claims, GameMode mode) {
        return !claims.isEmpty() || mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR;
    }
}
