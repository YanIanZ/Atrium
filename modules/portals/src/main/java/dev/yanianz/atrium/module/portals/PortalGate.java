package dev.yanianz.atrium.module.portals;

import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Decides what happens when a player entered one or more portals in one move: the first portal (by name)
 * the player may use wins, then the shared re-entry cooldown is acquired. Pure logic, no Bukkit calls.
 */
final class PortalGate {
    enum Outcome { NOTHING, DENIED, COOLDOWN, USE }

    /**
     * @param portal    the portal concerned (null for {@link Outcome#NOTHING})
     * @param remaining cooldown left for {@link Outcome#COOLDOWN}, else zero
     */
    record Decision(Outcome outcome, @Nullable Portal portal, Duration remaining) {}

    private PortalGate() {}

    /**
     * @param entered  portals entered by the move, in name order
     * @param allowed  permission check
     * @param acquire  atomically starts the cooldown; returns {@link Duration#ZERO} on success or the time left.
     *                 Only called for a portal the player may use, so denied portals never start a cooldown.
     */
    static Decision decide(List<Portal> entered, Predicate<Portal> allowed, Supplier<Duration> acquire) {
        if (entered.isEmpty()) {
            return new Decision(Outcome.NOTHING, null, Duration.ZERO);
        }
        for (Portal portal : entered) {
            if (!allowed.test(portal)) {
                continue;
            }
            Duration left = acquire.get();
            if (left.isPositive()) {
                return new Decision(Outcome.COOLDOWN, portal, left);
            }
            return new Decision(Outcome.USE, portal, Duration.ZERO);
        }
        return new Decision(Outcome.DENIED, entered.getFirst(), Duration.ZERO);
    }
}
