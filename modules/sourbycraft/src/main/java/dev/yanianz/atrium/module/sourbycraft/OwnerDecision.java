package dev.yanianz.atrium.module.sourbycraft;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Decides what to do with a player's in-memory profile-scoped data (homes, /back, kit usage) once the profile
 * system reported which profile is active. Pure; {@link ProfileOwnership} executes the result.
 *
 * <p>Why it exists: on the official SourbyCraft API a switch into a profile that holds no Atrium data never
 * calls the adapter's {@code apply} (there is no {@code applyMissing} and no switch event), so the previous
 * profile's data would simply stay. Atrium therefore remembers which profile its data belongs to (the
 * <em>owner</em>) and compares it with the active profile whenever it learns it.
 *
 * <p>Decision table (first matching row wins):
 * <pre>
 * observation                 pending restore                                  action
 * -------------------------   ----------------------------------------------   ------------------------------
 * no active profile           any                                              NONE
 * started before the restore  yes                                              NONE   (older than the restore)
 * active == tag               yes, tagged                                      CONFIRM
 * active != previous owner    yes, untagged                                    ADOPT  (new profile reported)
 * within the settle time      yes                                              WAIT   (switch not committed yet)
 * after the settle time       yes, untagged                                    ADOPT  (restore was for this one)
 * after the settle time       yes, tagged, active != tag                       CLEAR  (KEEP: ADOPT)
 * any                         no, owner unknown                                ADOPT  (data predates tracking)
 * active == owner             no                                               NONE
 * active != owner             no                                               CLEAR  (KEEP: ADOPT)
 * </pre>
 * A <em>restore</em> is SourbyCraft calling the adapter's {@code apply} (or {@code applyMissing} on the
 * extended API); its <em>tag</em> is the owner recorded in the restored data, absent in data written by
 * older Atrium builds and for {@code applyMissing}. SourbyCraft commits a switch only after the restore and
 * the teleport, so for a while after a restore the profile system may still report the previous profile:
 * that is what the settle time covers.
 */
final class OwnerDecision {
    enum Action {
        /** Nothing to do. */
        NONE,
        /** A restore is in flight and the observation may predate its commit: decide again later. */
        WAIT,
        /** The restore landed on the tagged profile: the owner is confirmed, the restore is no longer pending. */
        CONFIRM,
        /** The data belongs to the active profile: make it the owner and keep the data. */
        ADOPT,
        /** The data belongs to another profile: clear it to empty, then make the active profile the owner. */
        CLEAR
    }

    /**
     * A restore by the profile system that is not yet confirmed.
     *
     * @param atMillis      when it happened ({@link ProfileOwnership}'s clock)
     * @param tag           the owner recorded in the restored data, or null
     * @param previousOwner the owner before the restore, or null
     */
    record Pending(long atMillis, @Nullable UUID tag, @Nullable UUID previousOwner) {}

    /** What the profile system reported: the active profile (null = none) and when the lookup started. */
    record Observation(@Nullable UUID active, long startedAtMillis) {}

    private OwnerDecision() {}

    /**
     * @param owner       profile the in-memory data belongs to, or null when unknown
     * @param pending     unconfirmed restore, or null
     * @param observation latest observation
     * @param missing     {@code profile-missing-state}: KEEP adopts where EMPTY clears
     * @param settleMillis how long after a restore a differing observation is not trusted
     */
    static Action decide(@Nullable UUID owner, @Nullable Pending pending, Observation observation,
            ProfileScopes.MissingState missing, long settleMillis) {
        Objects.requireNonNull(observation, "observation");
        UUID active = observation.active();
        if (active == null) {
            return Action.NONE;
        }
        Action mismatch = missing == ProfileScopes.MissingState.KEEP ? Action.ADOPT : Action.CLEAR;
        if (pending != null) {
            if (observation.startedAtMillis() < pending.atMillis()) {
                return Action.NONE;
            }
            if (pending.tag() != null && pending.tag().equals(active)) {
                return Action.CONFIRM;
            }
            if (pending.tag() == null && !active.equals(pending.previousOwner())) {
                return Action.ADOPT;
            }
            if (observation.startedAtMillis() - pending.atMillis() < settleMillis) {
                return Action.WAIT;
            }
            return pending.tag() == null ? Action.ADOPT : mismatch;
        }
        if (owner == null) {
            return Action.ADOPT;
        }
        return owner.equals(active) ? Action.NONE : mismatch;
    }
}
