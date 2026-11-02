package dev.yanianz.atrium.module.worldrules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class GameRuleValuesTest {
    @Test
    void booleansMustBeTrueOrFalse() {
        assertEquals(Boolean.TRUE, GameRuleValues.parse(Boolean.class, "true"));
        assertEquals(Boolean.TRUE, GameRuleValues.parse(Boolean.class, " TRUE "));
        assertEquals(Boolean.FALSE, GameRuleValues.parse(Boolean.class, "False"));
        assertThrows(IllegalArgumentException.class, () -> GameRuleValues.parse(Boolean.class, "yes"));
        assertThrows(IllegalArgumentException.class, () -> GameRuleValues.parse(Boolean.class, "1"));
        assertThrows(IllegalArgumentException.class, () -> GameRuleValues.parse(Boolean.class, ""));
    }

    @Test
    void integersAreWhole32BitValues() {
        assertEquals(42, GameRuleValues.parse(Integer.class, "42"));
        assertEquals(-5, GameRuleValues.parse(Integer.class, " -5 "));
        assertThrows(IllegalArgumentException.class, () -> GameRuleValues.parse(Integer.class, "1.5"));
        assertThrows(IllegalArgumentException.class, () -> GameRuleValues.parse(Integer.class, "true"));
        assertThrows(IllegalArgumentException.class,
                () -> GameRuleValues.parse(Integer.class, "999999999999999999999"));
    }

    @Test
    void unsupportedTypesAreRejectedInsteadOfGuessed() {
        assertEquals(GameRuleValues.Kind.UNSUPPORTED, GameRuleValues.kindOf(Double.class));
        assertThrows(IllegalArgumentException.class, () -> GameRuleValues.parse(Double.class, "1.0"));
        assertEquals("true", GameRuleValues.example(Boolean.class));
        assertEquals("0", GameRuleValues.example(Integer.class));
    }

    @Test
    void kindsMatchBoxedAndPrimitiveTypes() {
        assertEquals(GameRuleValues.Kind.BOOLEAN, GameRuleValues.kindOf(boolean.class));
        assertEquals(GameRuleValues.Kind.INTEGER, GameRuleValues.kindOf(int.class));
    }
}
