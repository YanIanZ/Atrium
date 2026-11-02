package dev.yanianz.atrium.module.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class EssentialsMoneyTest {
    private static BigDecimal parse(String raw) {
        return EssentialsMoney.parse(raw).orElseThrow(() -> new AssertionError("rejected: " + raw));
    }

    @Test
    void lonelyCommaWithOneOrTwoDigitsIsADecimalSeparator() {
        // Regression: commas were stripped, so "12,50" became 1250.
        assertEquals(new BigDecimal("12.50"), parse("12,50"));
        assertEquals(new BigDecimal("1.5"), parse("1,5"));
        assertEquals(new BigDecimal("0.99"), parse("€0,99"));
    }

    @Test
    void otherCommasAreThousandsSeparators() {
        assertEquals(new BigDecimal("1234"), parse("1,234"));
        assertEquals(new BigDecimal("12500"), parse("12,500"));
        assertEquals(new BigDecimal("1234567.89"), parse("$1,234,567.89"));
        assertEquals(new BigDecimal("1234.50"), parse("1,234.50"));
    }

    @Test
    void ambiguousValuesAreRejected() {
        assertTrue(EssentialsMoney.parse("1.234,56").isEmpty());
        assertTrue(EssentialsMoney.parse("12,5000").isEmpty());
        assertTrue(EssentialsMoney.parse("1,23,456").isEmpty());
        assertTrue(EssentialsMoney.parse("1234,567").isEmpty());
        assertTrue(EssentialsMoney.parse(",50").isEmpty());
        assertTrue(EssentialsMoney.parse("12,").isEmpty());
    }

    @Test
    void plainNumbersStillParse() {
        assertEquals(new BigDecimal("1234.5678"), parse("1234.5678"));
        assertEquals(new BigDecimal("1.0E7"), parse("1.0E7"));
    }
}
