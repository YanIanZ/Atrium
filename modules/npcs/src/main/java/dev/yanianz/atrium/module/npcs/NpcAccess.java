package dev.yanianz.atrium.module.npcs;

import java.math.BigDecimal;
import java.time.Duration;

/** Pure permission/cooldown/cost rule for one NPC click. */
final class NpcAccess {
    private NpcAccess() {}

    enum Outcome {
        ALLOWED,
        NO_PERMISSION,
        COOLDOWN,
        NO_ECONOMY,
        INSUFFICIENT_FUNDS
    }

    /**
     * Decides whether the click may proceed.
     *
     * @param permission   required node, empty for none
     * @param hasPermission {@code player.hasPermission(permission)}
     * @param cooldownLeft  time left from {@code tryAcquire}; zero when the cooldown was free
     * @param cost         configured cost; zero or negative for free
     * @param balance      cached balance; {@code null} when the economy is absent or the player is unknown
     */
    static Outcome decide(String permission, boolean hasPermission, Duration cooldownLeft, BigDecimal cost,
            BigDecimal balance) {
        if (permission != null && !permission.isBlank() && !hasPermission) {
            return Outcome.NO_PERMISSION;
        }
        if (cooldownLeft != null && !cooldownLeft.isZero() && !cooldownLeft.isNegative()) {
            return Outcome.COOLDOWN;
        }
        BigDecimal amount = cost == null ? BigDecimal.ZERO : cost;
        if (amount.signum() <= 0) {
            return Outcome.ALLOWED;
        }
        if (balance == null) {
            return Outcome.NO_ECONOMY;
        }
        return balance.compareTo(amount) >= 0 ? Outcome.ALLOWED : Outcome.INSUFFICIENT_FUNDS;
    }
}
