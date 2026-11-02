package dev.yanianz.atrium.module.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DamageControlRulesTest {
    private static CombatConfig.DamageRule rule(String cause, double multiplier, List<String> permissions,
            List<String> worlds) {
        CombatConfig.DamageRule rule = new CombatConfig.DamageRule();
        rule.cause = cause;
        rule.multiplier = multiplier;
        rule.permissions = permissions;
        rule.worlds = worlds;
        return rule;
    }

    @Test
    void firstMatchingRuleWins() {
        List<CombatConfig.DamageRule> rules = List.of(
                rule("FALL", 0.5, List.of(), List.of()),
                rule("FALL", 0.25, List.of(), List.of()));
        assertEquals(0.5, DamageControlRules.multiplier(rules, "FALL", "world", permission -> true));
    }

    @Test
    void causeMatchesIgnoringCaseAndDashes() {
        assertTrue(DamageControlRules.causeMatches("fire_tick", "FIRE_TICK"));
        assertTrue(DamageControlRules.causeMatches("fire-tick", "FIRE_TICK"));
        assertTrue(DamageControlRules.causeMatches("*", "FALL"));
        assertTrue(DamageControlRules.causeMatches("ALL", "FALL"));
        assertTrue(DamageControlRules.causeMatches("", "FALL"));
        assertTrue(DamageControlRules.causeMatches(null, "FALL"));
        assertFalse(DamageControlRules.causeMatches("FALL", "FIRE"));
        assertFalse(DamageControlRules.causeMatches("FALL", null));
    }

    @Test
    void noRuleMeansUnchangedDamage() {
        assertEquals(1.0, DamageControlRules.multiplier(null, "FALL", "world", permission -> true));
        assertEquals(1.0, DamageControlRules.multiplier(List.of(), "FALL", "world", permission -> true));
        assertEquals(1.0, DamageControlRules.multiplier(List.of(rule("FIRE", 0.2, List.of(), List.of())),
                "FALL", "world", permission -> true));
    }

    @Test
    void worldsLimitARuleButAnEmptyListMatchesEverything() {
        List<CombatConfig.DamageRule> worlds = List.of(rule("*", 2.0, List.of(), List.of("Arena")));
        assertEquals(2.0, DamageControlRules.multiplier(worlds, "FALL", "arena", permission -> true));
        assertEquals(1.0, DamageControlRules.multiplier(worlds, "FALL", "world", permission -> true));
        List<CombatConfig.DamageRule> every = List.of(rule("*", 0.5, List.of(), List.of()));
        assertEquals(0.5, DamageControlRules.multiplier(every, "FALL", "world", permission -> true));
        assertEquals(0.5, DamageControlRules.multiplier(every, "FALL", null, permission -> true));
    }

    @Test
    void permissionsAreAnyOfAndEmptyMatchesEveryone() {
        List<CombatConfig.DamageRule> rules = List.of(
                rule("FALL", 0.1, List.of("rank.vip", "rank.staff"), List.of()));
        Set<String> granted = Set.of("rank.vip");
        assertEquals(0.1, DamageControlRules.multiplier(rules, "FALL", "world", granted::contains));
        assertEquals(1.0, DamageControlRules.multiplier(rules, "FALL", "world", permission -> false));
        List<CombatConfig.DamageRule> everyone = List.of(rule("FALL", 0.75, List.of(), List.of()));
        assertEquals(0.75, DamageControlRules.multiplier(everyone, "FALL", "world", permission -> false));
    }

    @Test
    void multipliersAreClamped() {
        assertEquals(0.0, DamageControlRules.clamp(-2.0));
        assertEquals(0.0, DamageControlRules.clamp(0.0));
        assertEquals(2.5, DamageControlRules.clamp(2.5));
        assertEquals(100.0, DamageControlRules.clamp(500.0));
        assertEquals(1.0, DamageControlRules.clamp(Double.NaN));
        assertEquals(1.0, DamageControlRules.clamp(Double.POSITIVE_INFINITY));
        assertEquals(0.0, DamageControlRules.multiplier(
                List.of(rule("FALL", -3.0, List.of(), List.of())), "FALL", "world", permission -> true));
    }
}
