package dev.yanianz.atrium.module.holograms;

/**
 * The numbers behind the hologram animations. Pure arithmetic, so the fade curve and the rotation wrap are unit
 * tested instead of being tuned in game.
 *
 * <ul>
 *   <li><b>Fade</b>: after a page change the new page grows from {@code 0.05} to its full size over the
 *       configured ticks, easing in and out (smoothstep). Only the scale changes, so nothing has to be respawned
 *       and the click box stays where it is.</li>
 *   <li><b>Rotation</b>: the yaw advances with real elapsed ticks. It does not depend on how many players watch,
 *       so the speed is the same with one viewer and with fifty, and a slow rotation still completes instead of
 *       being truncated per tick.</li>
 * </ul>
 */
final class Animation {
    /** Smallest scale of a fading page, so the entities stay visible instead of popping in at zero. */
    static final float MIN_FADE = 0.05f;

    private Animation() {}

    /**
     * Fade multiplier for {@code elapsedTicks} since the page was rendered. 1 when the fade is off or finished.
     *
     * @param fadeTicks total length of the fade; 0 disables it
     */
    static float fade(long elapsedTicks, int fadeTicks) {
        if (fadeTicks <= 0 || elapsedTicks >= fadeTicks) {
            return 1f;
        }
        float t = Math.max(0f, Math.min(1f, elapsedTicks / (float) fadeTicks));
        float eased = t * t * (3f - 2f * t);
        return MIN_FADE + (1f - MIN_FADE) * eased;
    }

    /**
     * Yaw after {@code elapsedTicks} at {@code degreesPerSecond}, added to {@code baseYaw} and wrapped into
     * {@code 0..360}. A negative speed rotates the other way.
     */
    static float yaw(float baseYaw, float degreesPerSecond, long elapsedTicks) {
        double degrees = baseYaw + degreesPerSecond * (elapsedTicks / 20.0);
        double wrapped = degrees % 360.0;
        if (wrapped < 0) {
            wrapped += 360.0;
        }
        return (float) wrapped;
    }

    /** True while a fade started at {@code startedAt} is still running. */
    static boolean fading(long ticks, long startedAt, int fadeTicks) {
        return fadeTicks > 0 && ticks - startedAt < fadeTicks;
    }
}
