package dev.yanianz.atrium.module.playerplus;

import org.jspecify.annotations.Nullable;

/**
 * Persisted player-plus data: remaining temporary flight time, purchased flight charge, the auto-recharge
 * preference and the active cuff. All values are replaced as a whole through the user component.
 *
 * <p>Remaining temporary flight is stored as a duration, never as an expiry timestamp, so time spent
 * offline does not count.
 */
public record PlayerPlusState(long tflyRemainingMillis, long flightChargeSeconds, boolean autoRecharge,
        @Nullable CuffPoint cuff) {

    public PlayerPlusState {
        tflyRemainingMillis = Math.max(0, tflyRemainingMillis);
        flightChargeSeconds = Math.max(0, flightChargeSeconds);
    }

    public static PlayerPlusState defaults(PlayerPlusConfig config) {
        PlayerPlusConfig.FlightCharge charge = config.flightCharge;
        return new PlayerPlusState(0, Math.min(Math.max(0, charge.startingSeconds), Math.max(0, charge.maxSeconds)),
                charge.autoRechargeDefault, null);
    }

    public boolean cuffed() {
        return cuff != null;
    }

    public PlayerPlusState withTflyRemaining(long millis) {
        return new PlayerPlusState(Math.max(0, millis), flightChargeSeconds, autoRecharge, cuff);
    }

    public PlayerPlusState withFlightCharge(long seconds) {
        return new PlayerPlusState(tflyRemainingMillis, Math.max(0, seconds), autoRecharge, cuff);
    }

    public PlayerPlusState withAutoRecharge(boolean value) {
        return new PlayerPlusState(tflyRemainingMillis, flightChargeSeconds, value, cuff);
    }

    public PlayerPlusState withCuff(@Nullable CuffPoint point) {
        return new PlayerPlusState(tflyRemainingMillis, flightChargeSeconds, autoRecharge, point);
    }
}
