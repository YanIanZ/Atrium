package dev.yanianz.atrium.module.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SudoRulesTest {
    @Test
    void normalTargetsAreAllowed() {
        assertEquals(SudoRules.Decision.ALLOWED, SudoRules.decide(false, true, false, false));
        assertEquals(SudoRules.Decision.ALLOWED, SudoRules.decide(true, true, false, false));
    }

    @Test
    void exemptWinsOverEverythingElse() {
        assertEquals(SudoRules.Decision.TARGET_EXEMPT, SudoRules.decide(false, true, false, true));
        assertEquals(SudoRules.Decision.TARGET_EXEMPT, SudoRules.decide(true, true, true, true));
    }

    @Test
    void onlyConsoleMaySudoAnOperator() {
        assertEquals(SudoRules.Decision.TARGET_OPERATOR, SudoRules.decide(false, true, true, false));
        assertEquals(SudoRules.Decision.ALLOWED, SudoRules.decide(true, true, true, false));
    }

    @Test
    void consoleOrAnyNonPlayerIsNeverATarget() {
        assertEquals(SudoRules.Decision.TARGET_NOT_PLAYER, SudoRules.decide(true, false, false, false));
        assertEquals(SudoRules.Decision.TARGET_NOT_PLAYER, SudoRules.decide(false, false, true, false));
        assertEquals(SudoRules.Decision.TARGET_NOT_PLAYER, SudoRules.decide(true, false, true, true));
    }
}
