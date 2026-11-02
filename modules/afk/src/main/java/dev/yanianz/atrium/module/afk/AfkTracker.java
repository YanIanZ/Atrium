package dev.yanianz.atrium.module.afk;

import dev.yanianz.atrium.api.afk.AfkReason;
import java.util.UUID;

/**
 * Mutable AFK state of one online player. All access is synchronized because moves run on the player's
 * region thread while the async chat event may observe the same tracker.
 */
final class AfkTracker {
    enum ActivityResult {
        /** The movement was too small, or was treated as a push/vehicle movement. */
        IGNORED,
        /** The movement happened inside the anti-AFK-pool box and does not refresh the idle timer. */
        POOLED,
        /** Real activity: the idle timer was refreshed. */
        ACTIVE
    }

    record Exit(
            long durationMillis,
            boolean restoreSleepingIgnored,
            boolean previousSleepingIgnored,
            boolean restoreInvulnerable,
            boolean previousInvulnerable) {
        static final Exit NONE = new Exit(0, false, false, false, false);
    }

    private final UUID uuid;
    private long lastActivityAt;
    private long afkSince;
    private AfkReason reason;
    private String message = "";
    private boolean actionsRun;
    private long retryAfter;

    private AfkPosition lastPosition;
    private AfkPosition poolAnchor;
    private long poolSince;

    private boolean sleepingIgnoredApplied;
    private boolean previousSleepingIgnored;
    private boolean invulnerableApplied;
    private boolean previousInvulnerable;

    AfkTracker(UUID uuid, long now, AfkPosition position) {
        this.uuid = uuid;
        this.lastActivityAt = now;
        this.lastPosition = position;
        this.poolAnchor = position;
        this.poolSince = now;
    }

    UUID uuid() {
        return uuid;
    }

    synchronized ActivityResult recordMovement(AfkPosition current, MovementFlags flags, long now, AfkRules rules) {
        if (lastPosition == null) {
            lastPosition = current;
            markActive(now, current);
            return ActivityResult.ACTIVE;
        }
        AfkPosition previous = lastPosition;
        if (!previous.world().equals(current.world())) {
            lastPosition = current;
            markActive(now, current);
            return ActivityResult.ACTIVE;
        }

        double horizontal = previous.horizontalDistance(current);
        double vertical = previous.verticalDistance(current);

        if (rules.ignorePushed() && !flags.activeInput()
                && (horizontal > rules.maxStep() || vertical > rules.maxStep())) {
            // Piston slams, teleports and fast vehicle motion move far more than one tick of walking.
            lastPosition = current;
            return ActivityResult.IGNORED;
        }
        if (rules.ignorePushed() && flags.pushed() && !flags.activeInput()) {
            lastPosition = current;
            return ActivityResult.IGNORED;
        }

        boolean moved = horizontal >= rules.movementThreshold()
                || vertical >= rules.movementThreshold()
                || (rules.countRotation()
                        && previous.rotationDistance(current) >= rules.rotationThresholdDegrees());
        if (!moved) {
            // Keep the old position so several sub-threshold moves can accumulate into real movement.
            return ActivityResult.IGNORED;
        }

        if (rules.antiAfkPool() && poolAnchor != null
                && poolAnchor.horizontalDistance(current) <= rules.poolBoxSize()) {
            if (now - poolSince >= rules.poolWindowMillis()) {
                // The player has stayed in the same small area for the whole window. Keep the anchor
                // fixed so a macro cannot earn one activity per window.
                lastPosition = current;
                return ActivityResult.POOLED;
            }
            // Grace window: count the movement but do not restart the window.
            lastPosition = current;
            lastActivityAt = now;
            retryAfter = 0;
            return ActivityResult.ACTIVE;
        }

        lastPosition = current;
        markActive(now, current);
        return ActivityResult.ACTIVE;
    }

    /** Counts chat, commands, interaction or an inventory click as activity. */
    synchronized void recordActivity(long now) {
        lastActivityAt = now;
        retryAfter = 0;
        // The next movement sets a fresh anchor and therefore counts immediately.
        poolAnchor = null;
        poolSince = now;
    }

    private void markActive(long now, AfkPosition current) {
        lastActivityAt = now;
        retryAfter = 0;
        poolAnchor = current;
        poolSince = now;
    }

    synchronized boolean isAfk() {
        return afkSince != 0;
    }

    synchronized long afkSince() {
        return afkSince;
    }

    synchronized long lastActivityAt() {
        return lastActivityAt;
    }

    synchronized AfkReason reason() {
        return reason;
    }

    synchronized String message() {
        return message;
    }

    synchronized boolean automatic() {
        return reason == AfkReason.AUTOMATIC;
    }

    /** False while a cancelled automatic transition is postponed. */
    synchronized boolean canAutoChange(long now) {
        return now >= retryAfter;
    }

    synchronized void postpone(long until) {
        retryAfter = Math.max(retryAfter, until);
    }

    /** Claims the one-shot action step of this AFK session. */
    synchronized boolean claimActions() {
        if (actionsRun) {
            return false;
        }
        actionsRun = true;
        return true;
    }

    /**
     * Starts an AFK session. Returns false when the player is already AFK.
     *
     * @param sleepApplied true when the caller is about to set sleepingIgnored
     * @param previousSleepingIgnored value to restore when sleepApplied is true
     * @param invulnerableApplied true when the caller is about to set invulnerable
     * @param previousInvulnerable value to restore when invulnerableApplied is true
     */
    synchronized boolean beginAfk(
            long now,
            AfkReason reason,
            String message,
            boolean sleepApplied,
            boolean previousSleepingIgnored,
            boolean invulnerableApplied,
            boolean previousInvulnerable) {
        if (afkSince != 0) {
            return false;
        }
        this.afkSince = now;
        this.reason = reason;
        this.message = message == null ? "" : message;
        this.actionsRun = false;
        this.sleepingIgnoredApplied = sleepApplied;
        this.previousSleepingIgnored = previousSleepingIgnored;
        this.invulnerableApplied = invulnerableApplied;
        this.previousInvulnerable = previousInvulnerable;
        this.poolAnchor = lastPosition;
        this.poolSince = now;
        return true;
    }

    /** Ends the AFK session and returns what the caller must restore. */
    synchronized Exit endAfk(long now) {
        if (afkSince == 0) {
            return Exit.NONE;
        }
        Exit exit = new Exit(
                Math.max(0, now - afkSince),
                sleepingIgnoredApplied,
                previousSleepingIgnored,
                invulnerableApplied,
                previousInvulnerable);
        afkSince = 0;
        reason = null;
        message = "";
        actionsRun = false;
        lastActivityAt = now;
        retryAfter = 0;
        poolAnchor = null;
        poolSince = now;
        sleepingIgnoredApplied = false;
        invulnerableApplied = false;
        return exit;
    }
}
