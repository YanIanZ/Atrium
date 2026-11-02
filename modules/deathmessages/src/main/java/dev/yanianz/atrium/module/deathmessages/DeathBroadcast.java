package dev.yanianz.atrium.module.deathmessages;

/** Pure radius rule: {@code 0} (or less) is global, otherwise same world and within the radius. */
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
}
