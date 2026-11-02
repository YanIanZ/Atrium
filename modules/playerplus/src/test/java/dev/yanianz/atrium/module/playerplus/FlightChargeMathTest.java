package dev.yanianz.atrium.module.playerplus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FlightChargeMathTest {
    private static final BigDecimal TEN = new BigDecimal("10");

    @Test
    void buyIsLimitedByRequestSpaceAndBalance() {
        // Enough money for 250 seconds, asking for 500, with 900 free.
        assertEquals(250, FlightChargeMath.affordable(new BigDecimal("2500"), TEN, 500, 100, 1000));
        // The cap limits the purchase before the balance does.
        assertEquals(100, FlightChargeMath.affordable(new BigDecimal("2500"), TEN, 500, 900, 1000));
        // The request limits it when both balance and space are larger.
        assertEquals(50, FlightChargeMath.affordable(new BigDecimal("2500"), TEN, 50, 0, 1000));
    }

    @Test
    void unaffordableOrInvalidPurchasesBuyNothing() {
        assertEquals(0, FlightChargeMath.affordable(BigDecimal.ZERO, TEN, 500, 0, 1000));
        assertEquals(0, FlightChargeMath.affordable(new BigDecimal("9"), TEN, 500, 0, 1000));
        assertEquals(99, FlightChargeMath.affordable(new BigDecimal("999"), TEN, 500, 0, 1000));
        assertEquals(0, FlightChargeMath.affordable(new BigDecimal("2500"), BigDecimal.ZERO, 500, 0, 1000));
        assertEquals(0, FlightChargeMath.affordable(null, TEN, 500, 0, 1000));
        assertEquals(0, FlightChargeMath.affordable(new BigDecimal("2500"), TEN, 500, 1000, 1000));
    }

    @Test
    void costIsExactAndUsesTheAffordableUnitsOnly() {
        BigDecimal unit = new BigDecimal("1.25");
        long units = FlightChargeMath.affordable(new BigDecimal("3.75"), unit, 500, 0, 1000);
        assertEquals(3, units);
        assertEquals(new BigDecimal("3.75"), FlightChargeMath.cost(units, unit));
    }

    @Test
    void addingChargeNeverExceedsTheCap() {
        assertEquals(150, FlightChargeMath.addClamped(100, 50, 200));
        assertEquals(200, FlightChargeMath.addClamped(100, 500, 200));
        assertEquals(200, FlightChargeMath.addClamped(Long.MAX_VALUE, 50, 200));
        assertEquals(0, FlightChargeMath.addClamped(0, -5, 200));
    }

    @Test
    void fractionalExperiencePricesRoundUp() {
        assertEquals(2, FlightChargeMath.expCost(1, 1.9));
        assertEquals(4, FlightChargeMath.expCost(2, 1.9));
        assertEquals(0, FlightChargeMath.expCost(0, 1.9));
        assertEquals(0, FlightChargeMath.expCost(1, 0));
    }

    @Test
    void experienceAffordabilityFloorsAndCaps() {
        assertEquals(5, FlightChargeMath.expAffordable(10, 1.9, 500, 0, 1000));
        assertEquals(3, FlightChargeMath.expAffordable(100, 1.9, 3, 0, 1000));
        assertEquals(1, FlightChargeMath.expAffordable(100, 1.9, 500, 999, 1000));
        assertEquals(0, FlightChargeMath.expAffordable(0, 1.9, 500, 0, 1000));
    }

    @Test
    void tickIsRelativeSoAConcurrentPurchaseIsKept() {
        // Regression: tick read 100, a purchase added 50 (150 stored), tick wrote an absolute 99 -> 50 s lost.
        long stored = 100;
        stored = FlightChargeMath.credit(stored, 50, 1000).stored();
        stored = FlightChargeMath.consume(stored, 1, 1000);
        assertEquals(149, stored);
        assertEquals(0, FlightChargeMath.consume(0, 5, 1000));
        assertEquals(999, FlightChargeMath.consume(5000, 1, 1000), "a lowered cap is honoured");
    }

    @Test
    void clampedPurchaseReportsTheOverflowToRefund() {
        // Regression: two purchases computed from the same free space; the second one only partly fits.
        FlightChargeMath.Credit first = FlightChargeMath.credit(900, 100, 1000);
        assertEquals(1000, first.stored());
        assertEquals(100, first.added());
        FlightChargeMath.Credit second = FlightChargeMath.credit(first.stored(), 100, 1000);
        assertEquals(1000, second.stored());
        assertEquals(0, second.added());
        assertEquals(100, FlightChargeMath.overflow(100, second.added()));
        FlightChargeMath.Credit partial = FlightChargeMath.credit(950, 100, 1000);
        assertEquals(50, partial.added());
        assertEquals(50, FlightChargeMath.overflow(100, partial.added()));
        // Paid exactly units * price; refund + kept is the full amount.
        assertEquals(0, FlightChargeMath.cost(100, TEN).compareTo(
                FlightChargeMath.cost(partial.added(), TEN).add(FlightChargeMath.cost(50, TEN))));
        // Above a lowered cap nothing is taken away.
        assertEquals(1500, FlightChargeMath.credit(1500, 10, 1000).stored());
    }

    @Test
    void expRefundGivesBackTheUnusedPoints() {
        long paid = FlightChargeMath.expCost(100, 1.5);
        assertEquals(150, paid);
        assertEquals(75, FlightChargeMath.expRefund(paid, 50, 1.5));
        assertEquals(0, FlightChargeMath.expRefund(paid, 100, 1.5));
        assertEquals(paid, FlightChargeMath.expRefund(paid, 0, 1.5));
    }

    @Test
    void adminChangesAreRelative() {
        assertEquals(160, FlightChargeMath.adminApply("add", 150, 10, 1000));
        assertEquals(1000, FlightChargeMath.adminApply("add", 995, 10, 1000));
        assertEquals(140, FlightChargeMath.adminApply("take", 150, 10, 1000));
        assertEquals(0, FlightChargeMath.adminApply("take", 5, 10, 1000));
        assertEquals(1000, FlightChargeMath.adminApply("set", 5, 5000, 1000));
    }
}
