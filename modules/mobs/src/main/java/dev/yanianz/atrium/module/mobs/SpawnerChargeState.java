package dev.yanianz.atrium.module.mobs;

/**
 * Regenerating spawner charges of one player. Pure: the regeneration arithmetic is unit tested, including
 * the edge cases a naive implementation gets wrong (a zero cooldown and a clock that moves backwards).
 */
record SpawnerChargeState(int charges, long lastGiveMillis) {

    SpawnerChargeState {
        charges = Math.max(0, charges);
    }

    static SpawnerChargeState initial(int startingCharges, long now) {
        return new SpawnerChargeState(Math.max(0, startingCharges), now);
    }

    boolean hasCharge() {
        return charges > 0;
    }

    SpawnerChargeState spend() {
        return charges <= 0 ? this : new SpawnerChargeState(charges - 1, lastGiveMillis);
    }

    /**
     * Adds every full cooldown that passed since the last refill. While the player is at the cap the
     * reference time moves with the clock, so spending a charge right after logging in does not hand out
     * a free refill.
     */
    SpawnerChargeState refill(int maxCharges, long cooldownSeconds, long now) {
        int cap = Math.max(0, maxCharges);
        long period = Math.max(1, cooldownSeconds) * 1000L;
        if (charges >= cap) {
            return new SpawnerChargeState(cap, now);
        }
        long elapsed = now - lastGiveMillis;
        if (elapsed <= 0) {
            // The clock moved backwards (NTP correction): keep the reference in the future, do not refill.
            return this;
        }
        long gained = elapsed / period;
        if (gained <= 0) {
            return this;
        }
        long refilled = lastGiveMillis + gained * period;
        int total = (int) Math.min(cap, (long) charges + gained);
        return new SpawnerChargeState(total, total >= cap ? now : refilled);
    }

    /** Whole seconds until the next charge, or {@code 0} when already at the cap. */
    long secondsUntilNext(int maxCharges, long cooldownSeconds, long now) {
        if (charges >= Math.max(0, maxCharges)) {
            return 0;
        }
        long period = Math.max(1, cooldownSeconds) * 1000L;
        long remaining = lastGiveMillis + period - now;
        return remaining <= 0 ? 0 : (remaining + 999) / 1000;
    }
}
