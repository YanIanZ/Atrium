package dev.yanianz.atrium.module.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AmountSyntaxTest {
    @Test
    void plainAmountsSupportSuffixes() {
        assertEquals(new BigDecimal("2500.00"), AmountSyntax.resolve(AmountSyntax.parse("2.5k"), BigDecimal.ZERO, 2, new Random(1)));
        assertEquals(new BigDecimal("1000000.00"), AmountSyntax.resolve(AmountSyntax.parse("1m"), BigDecimal.ZERO, 2, new Random(1)));
        assertEquals(new BigDecimal("0.10"), AmountSyntax.resolve(AmountSyntax.parse("0.1"), BigDecimal.ZERO, 2, new Random(1)));
        assertFalse(AmountSyntax.parse("10").needsBase());
    }

    @Test
    void percentUsesTheBaseBalanceAndNeverRoundsUp() {
        AmountSyntax.Expression expression = AmountSyntax.parse("10%");
        assertTrue(expression.needsBase());
        assertEquals(new BigDecimal("9.99"), AmountSyntax.resolve(expression, new BigDecimal("99.99"), 2, new Random(1)));
        assertEquals(new BigDecimal("0.00"), AmountSyntax.resolve(expression, BigDecimal.ZERO, 2, new Random(1)));
        assertEquals(new BigDecimal("33.33"), AmountSyntax.resolve(AmountSyntax.parse("33.33%"), new BigDecimal("100"), 2, new Random(1)));
    }

    @Test
    void randomAmountsStayInsideTheRange() {
        AmountSyntax.Expression expression = AmountSyntax.parse("rand:10-20");
        assertFalse(expression.needsBase());
        Random random = new Random(42);
        for (int i = 0; i < 200; i++) {
            BigDecimal value = AmountSyntax.resolve(expression, BigDecimal.ZERO, 2, random);
            assertTrue(value.compareTo(new BigDecimal("10")) >= 0);
            assertTrue(value.compareTo(new BigDecimal("20")) <= 0);
        }
        assertEquals(new BigDecimal("7.00"), AmountSyntax.resolve(AmountSyntax.parse("rand:7-7"), BigDecimal.ZERO, 2, random));
    }

    @Test
    void randomPercentStaysInsideThePercentageRange() {
        AmountSyntax.Expression expression = AmountSyntax.parse("rand:5-15%");
        assertTrue(expression.needsBase());
        Random random = new Random(7);
        for (int i = 0; i < 200; i++) {
            BigDecimal value = AmountSyntax.resolve(expression, new BigDecimal("1000"), 2, random);
            assertTrue(value.compareTo(new BigDecimal("50")) >= 0);
            assertTrue(value.compareTo(new BigDecimal("150")) <= 0);
        }
    }

    @Test
    void invalidExpressionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> AmountSyntax.parse(""));
        assertThrows(IllegalArgumentException.class, () -> AmountSyntax.parse("-5"));
        assertThrows(IllegalArgumentException.class, () -> AmountSyntax.parse("1.2.3"));
        assertThrows(IllegalArgumentException.class, () -> AmountSyntax.parse("50%%"));
        assertThrows(IllegalArgumentException.class, () -> AmountSyntax.parse("rand:"));
        assertThrows(IllegalArgumentException.class, () -> AmountSyntax.parse("rand:20-10"));
        assertThrows(IllegalArgumentException.class, () -> AmountSyntax.parse("rand:1-2-3"));
        assertThrows(IllegalArgumentException.class, () -> AmountSyntax.resolve(AmountSyntax.parse("5%"), null, 2, new Random(1)));
    }

    @Test
    void parsingIgnoresCaseAndOuterSpaces() {
        assertInstanceOf(AmountSyntax.Fixed.class, AmountSyntax.parse(" 1K "));
        assertEquals(new BigDecimal("1500.00"), AmountSyntax.resolve(AmountSyntax.parse(" 1.5K "), BigDecimal.ZERO, 2, new Random(1)));
    }

    @Test
    void historicRandomSpellingsAreAccepted() {
        assertInstanceOf(AmountSyntax.Random.class, AmountSyntax.parse("%rand:1-5%"));
        AmountSyntax.Random random = (AmountSyntax.Random) AmountSyntax.parse("%rand/1-5%");
        assertEquals(new BigDecimal("1"), random.min());
        assertEquals(new BigDecimal("5"), random.max());
        assertInstanceOf(AmountSyntax.RandomPercent.class, AmountSyntax.parse("%rand:1-5%%"));
    }
}
