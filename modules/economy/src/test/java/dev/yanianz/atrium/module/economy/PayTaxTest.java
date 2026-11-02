package dev.yanianz.atrium.module.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

class PayTaxTest {
    private static PayTax tax(String flat, String percent, String min, String max, boolean onTop) {
        return new PayTax(new BigDecimal(flat), new BigDecimal(percent), new BigDecimal(min),
                new BigDecimal(max), onTop);
    }

    @Test
    void flatAndPercentAreAdded() {
        PayTax tax = tax("1", "5", "0", "0", false);
        assertEquals(new BigDecimal("6.00"), tax.tax(new BigDecimal("100"), 2, RoundingMode.HALF_UP));
    }

    @Test
    void clampOnlyAppliesWhenAMaximumIsSet() {
        PayTax clamped = tax("0", "10", "2", "5", false);
        assertEquals(new BigDecimal("2.00"), clamped.tax(new BigDecimal("10"), 2, RoundingMode.HALF_UP));
        assertEquals(new BigDecimal("5.00"), clamped.tax(new BigDecimal("100"), 2, RoundingMode.HALF_UP));
        PayTax unclamped = tax("0", "10", "2", "0", false);
        assertEquals(new BigDecimal("10.00"), unclamped.tax(new BigDecimal("100"), 2, RoundingMode.HALF_UP));
        assertEquals(new BigDecimal("0.10"), unclamped.tax(new BigDecimal("1"), 2, RoundingMode.HALF_UP));
    }

    @Test
    void deductOnTopMovesTheTaxToTheSender() {
        PayTax tax = tax("0", "10", "0", "0", true);
        BigDecimal amount = new BigDecimal("100");
        BigDecimal value = tax.tax(amount, 2, RoundingMode.HALF_UP);
        assertEquals(new BigDecimal("110.00"), tax.senderPays(amount, value));
        assertEquals(new BigDecimal("100"), tax.receiverGets(amount, value));
    }

    @Test
    void taxIsTakenFromTheReceivedAmountOtherwise() {
        PayTax tax = tax("0", "10", "0", "0", false);
        BigDecimal amount = new BigDecimal("100");
        BigDecimal value = tax.tax(amount, 2, RoundingMode.HALF_UP);
        assertEquals(new BigDecimal("100"), tax.senderPays(amount, value));
        assertEquals(new BigDecimal("90.00"), tax.receiverGets(amount, value));
    }

    @Test
    void noneIsDisabled() {
        assertFalse(PayTax.NONE.enabled());
        assertEquals(BigDecimal.ZERO.setScale(2), PayTax.NONE.tax(BigDecimal.TEN, 2, RoundingMode.HALF_UP));
        assertTrue(tax("0", "1", "0", "0", false).enabled());
    }

    @Test
    void negativeConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> tax("-1", "0", "0", "0", false));
        assertThrows(IllegalArgumentException.class,
                () -> PayTax.NONE.tax(new BigDecimal("-1"), 2, RoundingMode.HALF_UP));
        assertThrows(IllegalArgumentException.class,
                () -> new PayTax(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, false)
                        .tax(BigDecimal.ONE, 11, RoundingMode.HALF_UP));
    }
}
