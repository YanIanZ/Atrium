package dev.yanianz.atrium.module.chatbubbles;

import java.util.UUID;

/**
 * Pure receiver filter. A bubble is shown to a viewer only when the viewer is not the ignored sender,
 * is in the same world, is inside the configured range, may see the speaker (vanish) and does not ignore
 * the speaker.
 */
final class BubbleReceivers {
    private BubbleReceivers() {}

    /** Snapshot of a viewer, updated on the owner's own thread. */
    record Viewer(UUID id, UUID worldId, double x, double y, double z) {
        double distanceSquared(double otherX, double otherY, double otherZ) {
            double dx = x - otherX;
            double dy = y - otherY;
            double dz = z - otherZ;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    static boolean accepts(Viewer viewer, UUID speakerId, UUID speakerWorld, double speakerX, double speakerY,
            double speakerZ, double viewRange, boolean visible, boolean ignoring, boolean hideFromSender) {
        if (viewer.id().equals(speakerId)) {
            // The sender is always next to the bubble, so only the explicit hide option matters.
            return !hideFromSender;
        }
        if (!visible || ignoring) {
            return false;
        }
        if (viewer.worldId() == null || !viewer.worldId().equals(speakerWorld)) {
            return false;
        }
        double range = Math.max(0d, viewRange);
        return viewer.distanceSquared(speakerX, speakerY, speakerZ) <= range * range;
    }
}
