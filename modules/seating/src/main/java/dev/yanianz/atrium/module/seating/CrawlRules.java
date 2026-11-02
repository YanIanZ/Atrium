package dev.yanianz.atrium.module.seating;

import org.jspecify.annotations.Nullable;

/**
 * Pure crawl state rules. The crawl itself is a client-side barrier block above the player's head,
 * so the only state that matters is which block was faked and when to stop.
 */
final class CrawlRules {
    /** The feet block whose head space is currently faked. */
    record Position(String world, int x, int y, int z) {}

    /** Why a crawl ended. Only player-initiated endings show a message. */
    enum StopReason {
        TOGGLE(true),
        SNEAK(true),
        JUMP(true),
        DAMAGE(true),
        DEATH(false),
        QUIT(false),
        TELEPORT(false),
        WORLD_CHANGE(false),
        VEHICLE(false),
        FLIGHT(true),
        GLIDE(true),
        GAMEMODE(false),
        DISABLED(false),
        INVALID(false);

        private final boolean notify;

        StopReason(boolean notify) {
            this.notify = notify;
        }

        boolean notifies() {
            return notify;
        }
    }

    private CrawlRules() {}

    /** The head block that receives the fake barrier. */
    static int barrierY(int feetY) {
        return feetY + 1;
    }

    static boolean sameBlock(@Nullable Position position, String world, int x, int y, int z) {
        return position != null
                && position.world().equals(world)
                && position.x() == x
                && position.y() == y
                && position.z() == z;
    }

    /** Conditions that make starting a crawl pointless or unsafe. */
    static boolean canStart(boolean sneaking, boolean insideVehicle, boolean flying, boolean gliding,
            boolean dead, boolean spectator, boolean inWater) {
        return !sneaking && !insideVehicle && !flying && !gliding && !dead && !spectator && !inWater;
    }

    /**
     * A crawl ends when the player jumps. Water is excluded because swimming upwards is normal while
     * crawling in water; a small step up is ignored as well.
     */
    static boolean jumped(double fromY, double toY, boolean inWater) {
        return !inWater && toY - fromY > 0.05;
    }

    /** Conditions checked on every move and on toggle events. */
    static boolean shouldStop(boolean dead, boolean spectator, boolean flying, boolean gliding,
            boolean insideVehicle) {
        return dead || spectator || flying || gliding || insideVehicle;
    }
}
