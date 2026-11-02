package dev.yanianz.atrium.module.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CmiBalanceParserTest {
    @Test
    void parsesPlainNumbers() {
        assertEquals(new BigDecimal("1234.50"), CmiBalanceParser.parse("1234.5").orElseThrow());
        assertEquals(new BigDecimal("1234.50"), CmiBalanceParser.parse("$1,234.50").orElseThrow());
        assertEquals(new BigDecimal("0.00"), CmiBalanceParser.parse("0").orElseThrow());
    }

    @Test
    void prefersTheDefaultWorldGroup() {
        assertEquals(new BigDecimal("100.00"), CmiBalanceParser.parse("default%%100;vip%%50").orElseThrow());
        assertEquals(new BigDecimal("25.00"), CmiBalanceParser.parse("vip%%50;default%%25").orElseThrow());
        assertEquals(new BigDecimal("7.00"), CmiBalanceParser.parse("{\"vip\":50,\"default\":7}").orElseThrow());
    }

    @Test
    void roundsToTwoDecimals() {
        assertEquals(new BigDecimal("42.13"), CmiBalanceParser.parse("{\"default\":42.125}").orElseThrow());
    }

    @Test
    void rejectsNegativeNonFiniteAndMalformedValues() {
        assertTrue(CmiBalanceParser.parse("-5").isEmpty());
        assertTrue(CmiBalanceParser.parse("NaN").isEmpty());
        assertTrue(CmiBalanceParser.parse("Infinity").isEmpty());
        assertTrue(CmiBalanceParser.parse("1e999999").isEmpty());
        assertTrue(CmiBalanceParser.parse("").isEmpty());
        assertTrue(CmiBalanceParser.parse(null).isEmpty());
    }
}
