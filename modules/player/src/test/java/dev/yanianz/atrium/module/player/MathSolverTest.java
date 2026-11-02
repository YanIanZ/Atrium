package dev.yanianz.atrium.module.player;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MathSolverTest {
    @Test void evaluatesPrecedenceParenthesesAndUnarySigns() {
        assertEquals(7.0, MathSolver.evaluate("1 + 2 * 3"), 1e-9);
        assertEquals(9.0, MathSolver.evaluate("(1 + 2) * 3"), 1e-9);
        assertEquals(-4.0, MathSolver.evaluate("-2^2"), 1e-9);
        assertEquals(4.0, MathSolver.evaluate("(-2)^2"), 1e-9);
        assertEquals(512.0, MathSolver.evaluate("2^3^2"), 1e-9);
        assertEquals(1.0, MathSolver.evaluate("7 % 3"), 1e-9);
        assertEquals(2.5, MathSolver.evaluate("5 / 2"), 1e-9);
        assertEquals(1500.0, MathSolver.evaluate("1.5e3"), 1e-9);
    }

    @Test void rejectsIncompleteOrUnsupportedInput() {
        for (String invalid : new String[]{"", "   ", "1 +", "2 ** 3", "()", "4 / 0", "abc", "1,5", "(1+2"}) {
            assertThrows(IllegalArgumentException.class, () -> MathSolver.evaluate(invalid), invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> MathSolver.evaluate("1".repeat(300)));
    }

    @Test void formatsResultsWithoutTrailingNoise() {
        assertEquals("4", MathSolver.format(4.0));
        assertEquals("2.5", MathSolver.format(2.5));
        assertEquals("0", MathSolver.format(-0.0));
        assertEquals("0.3333333333", MathSolver.format(1.0 / 3.0));
    }

    @Test void recognisesTheExpressionAlphabet() {
        assertTrue(MathSolver.looksValid("2 + 2"));
        assertTrue(MathSolver.looksValid("-(1.5e2)^2"));
        assertFalse(MathSolver.looksValid("2+2; op me"));
        assertFalse(MathSolver.looksValid(""));
        assertEquals("1 + 2", MathSolver.normalize(" 1 + 2 "));
    }
}
