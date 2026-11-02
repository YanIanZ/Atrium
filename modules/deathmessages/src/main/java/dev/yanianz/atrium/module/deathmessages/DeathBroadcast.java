package dev.yanianz.atrium.module.deathmessages;

/** Pure radius and visibility rules for a death broadcast. */
public final class DeathBroadcast {
    private DeathBroadcast() {}

    /**
     * @param radius configured broadcast radius in blocks; {@code <= 0} means every world
     * @param sameWorld whether the viewer is in the world where the player died
     * @param distanceSquared squared distance from the death location to the viewer
     */
    public static boolean receives(int radius, boolean sameWorld, double distanceSquared) {
        if (radius <= 0) {
            return true;
        }
        return sameWorld && distanceSquared <= (double) radius * radius;
    }

    /**
     * The full rule: a broadcast is hidden entirely while fewer than {@code hideBelowPlayers} players are
     * online, then the radius rule applies.
     *
     * @param online          players online on this server
     * @param hideBelowPlayers threshold below which death messages are not broadcast; {@code <= 0} disables
     */
    public static boolean receives(int radius, boolean sameWorld, double distanceSquared, int online,
            int hideBelowPlayers) {
        if (hideBelowPlayers > 0 && online < hideBelowPlayers) {
            return false;
        }
        return receives(radius, sameWorld, distanceSquared);
    }
}
