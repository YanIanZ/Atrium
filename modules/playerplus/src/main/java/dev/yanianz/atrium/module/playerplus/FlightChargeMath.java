package dev.yanianz.atrium.module.playerplus;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure purchase arithmetic for charged flight. Charge is stored in whole seconds and purchases are always
 * limited by the free space below the maximum and by what the player can actually pay; the caller then
 * withdraws exactly the returned cost in one transaction before any charge is added.
 */
final class FlightChargeMath {
    private FlightChargeMath() {}

    /** Free seconds below the cap. */
    static long space(long currentSeconds, long maxSeconds) {
        long current = Math.max(0, currentSeconds);
        long max = Math.max(0, maxSeconds);
        return Math.max(0, max - current);
    }

    /**
     * Seconds that may be bought: at most {@code requested}, at most the free space, and at most what
     * {@code balance} can pay for. Never negative.
     */
    static long affordable(BigDecimal balance, BigDecimal unitCost, long requested, long currentSeconds, long maxSeconds) {
        long wanted = Math.min(Math.max(0, requested), space(currentSeconds, maxSeconds));
        if (wanted == 0 || unitCost == null || unitCost.signum() <= 0 || balance == null || balance.signum() <= 0) {
            return 0;
        }
        long byBalance = balance.divide(unitCost, RoundingMode.DOWN).longValue();
        return Math.max(0, Math.min(wanted, byBalance));
    }

    /** Exact money cost of {@code seconds}. */
    static BigDecimal cost(long seconds, BigDecimal unitCost) {
        return unitCost.multiply(BigDecimal.valueOf(Math.max(0, seconds)));
    }

    /** Adds seconds while never exceeding the cap. */
    static long addClamped(long currentSeconds, long addedSeconds, long maxSeconds) {
        long current = Math.max(0, currentSeconds);
        long added = Math.max(0, addedSeconds);
        long sum;
        try {
            sum = Math.addExact(current, added);
        } catch (ArithmeticException e) {
            sum = Long.MAX_VALUE;
        }
        return Math.min(Math.max(0, maxSeconds), sum);
    }

    /** Experience points needed for {@code seconds}; fractional prices round up, never down. */
    static long expCost(long seconds, double expPerSecond) {
        if (seconds <= 0 || expPerSecond <= 0 || !Double.isFinite(expPerSecond)) {
            return 0;
        }
        return BigDecimal.valueOf(Math.max(0, seconds))
                .multiply(BigDecimal.valueOf(expPerSecond))
                .setScale(0, RoundingMode.CEILING)
                .longValue();
    }

    /** Seconds a player can pay for with {@code points}, also limited by the request and the cap. */
    static long expAffordable(long points, double expPerSecond, long requested, long currentSeconds, long maxSeconds) {
        long wanted = Math.min(Math.max(0, requested), space(currentSeconds, maxSeconds));
        if (wanted == 0 || points <= 0 || expPerSecond <= 0 || !Double.isFinite(expPerSecond)) {
            return 0;
        }
        long byPoints = (long) Math.floor(points / expPerSecond);
        return Math.max(0, Math.min(wanted, byPoints));
    }

    /**
     * Result of crediting purchased seconds in one atomic component update.
     *
     * @param stored the value to store (unchanged when there was no room)
     * @param added  seconds actually added; the caller refunds {@code units - added}
     */
    record Credit(long stored, long added) {}

    /** Adds up to {@code units} below the cap. A charge already at or above the cap is left untouched. */
    static Credit credit(long currentSeconds, long units, long maxSeconds) {
        long current = Math.max(0, currentSeconds);
        long added = Math.min(Math.max(0, units), space(current, maxSeconds));
        if (added <= 0) {
            return new Credit(currentSeconds, 0);
        }
        return new Credit(current + added, added);
    }

    /** Seconds that were paid for but could not be added. */
    static long overflow(long units, long added) {
        return Math.max(0, Math.max(0, units) - Math.max(0, added));
    }

    /** Experience points to give back when only {@code added} of the paid {@code units} were credited. */
    static long expRefund(long paidPoints, long added, double expPerSecond) {
        return Math.max(0, paidPoints - expCost(added, expPerSecond));
    }

    /**
     * One second of flight, applied relative to the stored value inside the atomic update so a purchase that lands
     * at the same time is never overwritten.
     */
    static long consume(long storedSeconds, long consumePerSecond, long maxSeconds) {
        long current = Math.min(Math.max(0, storedSeconds), Math.max(0, maxSeconds));
        return Math.max(0, current - Math.max(0, consumePerSecond));
    }

    /** Admin {@code add|take|set}, applied relative to the stored value inside the atomic update. */
    static long adminApply(String operation, long storedSeconds, long amount, long maxSeconds) {
        long current = Math.max(0, storedSeconds);
        return switch (operation) {
            case "add" -> addClamped(current, amount, maxSeconds);
            case "take" -> Math.max(0, current - Math.max(0, amount));
            default -> Math.min(Math.max(0, amount), Math.max(0, maxSeconds));
        };
    }
}
