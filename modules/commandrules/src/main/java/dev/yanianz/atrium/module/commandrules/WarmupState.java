package dev.yanianz.atrium.module.commandrules;

/**
 * Immutable warmup state machine. The module keeps one instance per warming player and replaces it on
 * every tick, so the test can verify the exact transitions without a scheduler.
 *
 * <pre>
 *   start(n) -> ACTIVE(n)
 *   tick()   -> ACTIVE(n-1) ... ACTIVE(1) -> COMPLETED(0)
 *   cancel() -> CANCELLED (only from ACTIVE)
 * </pre>
 */
public record WarmupState(int totalSeconds, int remainingSeconds, Phase phase) {
    public enum Phase { ACTIVE, COMPLETED, CANCELLED }

    public WarmupState {
        if (totalSeconds <= 0) {
            throw new IllegalArgumentException("totalSeconds must be positive");
        }
        if (remainingSeconds < 0 || remainingSeconds > totalSeconds) {
            throw new IllegalArgumentException("remainingSeconds out of range");
        }
    }

    public static WarmupState start(int seconds) {
        return new WarmupState(seconds, seconds, Phase.ACTIVE);
    }

    public boolean active() {
        return phase == Phase.ACTIVE;
    }

    /** Advances one second. A completed or cancelled state never changes again. */
    public WarmupState tick() {
        if (phase != Phase.ACTIVE) {
            return this;
        }
        int left = remainingSeconds - 1;
        return left <= 0
                ? new WarmupState(totalSeconds, 0, Phase.COMPLETED)
                : new WarmupState(totalSeconds, left, Phase.ACTIVE);
    }

    public WarmupState cancel() {
        return phase == Phase.ACTIVE
                ? new WarmupState(totalSeconds, remainingSeconds, Phase.CANCELLED)
                : this;
    }
}
