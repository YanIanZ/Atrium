package dev.yanianz.atrium.module.chatbubbles;

/**
 * Position math for the server-side fallback. A display that is teleported once per tick still trails one
 * tick behind the player, so the target includes the player's velocity (one tick of movement). The packet
 * renderer does not need this: its display is a client-side passenger and follows for free.
 */
final class BubbleAnchor {
    /** The client applies one tick of velocity after the last position we saw on the server. */
    static final double LEAD_TICKS = 1d;

    private BubbleAnchor() {}

    record Point(double x, double y, double z) {}

    static Point follow(double feetX, double feetY, double feetZ, double velocityX, double velocityY,
            double velocityZ, double eyeHeight, double yOffset) {
        return new Point(
                feetX + velocityX * LEAD_TICKS,
                feetY + eyeHeight + yOffset + velocityY * LEAD_TICKS,
                feetZ + velocityZ * LEAD_TICKS);
    }

    /** Vertical offset applied to the client-side display that rides the player at their feet. */
    static double translationY(double eyeHeight, double yOffset) {
        return eyeHeight + yOffset;
    }
}
