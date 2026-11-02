package dev.yanianz.atrium.module.economy;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class MoneyTest {
    private final Money money = new Money(2, RoundingMode.HALF_UP, new BigDecimal("99999999999999999999.99"), "$", "{symbol}{amount}");

    @Test void parsesSuffixesExactly() {
        assertEquals(new BigDecimal("1000.00"), money.parse("1k"));
        assertEquals(new BigDecimal("2500000.00"), money.parse("2.5M"));
        assertEquals(new BigDecimal("1000000000.00"), money.parse("1b"));
    }
    @Test void roundsOnceAfterMultiplication() {
        assertEquals(new BigDecimal("1005.00"), money.parse("1.005k"));
        assertEquals(new BigDecimal("1.01"), money.parse("1.005"));
    }
    @Test void rejectsInvalidAndNegativeBeforeRounding() {
        for (String text : new String[]{"NaN", "Infinity", "-0.001", "1e100", "1,000", "", "100000000000000000000"}) {
            assertThrows(IllegalArgumentException.class, () -> money.parse(text), text);
        }
    }
    @Test void formattingIsGroupedAndIndependentOfDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("$1,234,567.90", money.format(new BigDecimal("1234567.9")));
            assertEquals(new BigDecimal("2500.00"), money.parse("2.5k"));
        } finally { Locale.setDefault(previous); }
    }
    @Test void honorsConfiguredRounding() {
        Money down = new Money(2, RoundingMode.DOWN, BigDecimal.TEN, "", "{amount}");
        assertEquals(new BigDecimal("1.00"), down.parse("1.009"));
        assertThrows(IllegalArgumentException.class, () -> down.parse("10.001"));
    }
}
