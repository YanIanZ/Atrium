package dev.yanianz.atrium.api.teleport;

/**
 * How a teleport through {@link ExtendedTeleportService} is performed.
 *
 * @param warmup   apply the configured warmup for the cause (players with the bypass permission skip it)
 * @param cooldown check and start the configured cooldown for the cause
 * @param safety   whether the destination is moved to a safe spot first
 */
public record TeleportOptions(boolean warmup, boolean cooldown, Safety safety) {
    /** Warmup, cooldown and the configured safety rule for the cause. */
    public static final TeleportOptions DEFAULT = new TeleportOptions(true, true, Safety.CONFIG);

    /** No warmup and no cooldown (admin and plugin teleports); safety per config. */
    public static final TeleportOptions IMMEDIATE = new TeleportOptions(false, false, Safety.CONFIG);

    public TeleportOptions {
        if (safety == null) {
            safety = Safety.CONFIG;
        }
    }

    public TeleportOptions withSafety(Safety value) {
        return new TeleportOptions(warmup, cooldown, value);
    }

    public enum Safety {
        /** Use the server configuration for the teleport cause. */
        CONFIG,
        /** Always search a safe spot; the teleport fails with {@code UNSAFE} when none is found. */
        CHECK,
        /** Teleport to the exact location (for example after the player confirmed an unsafe home). */
        SKIP
    }
}
