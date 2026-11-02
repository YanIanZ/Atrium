package dev.yanianz.atrium.module.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogRulesTest {
    private static PerformanceConfig.ConsoleRule rule(String pattern, String mode, String action, String tag) {
        PerformanceConfig.ConsoleRule rule = new PerformanceConfig.ConsoleRule();
        rule.pattern = pattern;
        rule.mode = mode;
        rule.action = action;
        rule.tag = tag;
        return rule;
    }

    private static PerformanceConfig.ConsoleLog config(PerformanceConfig.ConsoleRule... rules) {
        PerformanceConfig.ConsoleLog config = new PerformanceConfig.ConsoleLog();
        config.enabled = true;
        config.rules = new ArrayList<>(List.of(rules));
        return config;
    }

    @Test
    void containsIsCaseInsensitiveAndHidesTheLine() {
        LogRules rules = LogRules.build(config(rule("moved wrongly", "contains", "hide", "")), (a, b) -> {});
        assertTrue(rules.decide("net.minecraft.Server", "Player moved WRONGLY!", false).hide());
        assertFalse(rules.decide("net.minecraft.Server", "Player joined", false).hide());
    }

    @Test
    void regexMatchesAnywhereAndCanTag() {
        LogRules rules = LogRules.build(config(rule("^\\[noisy\\].*$", "regex", "tag", "[noisy]")), (a, b) -> {});
        LogRules.Decision decision = rules.decide("com.example.Plugin", "[noisy] craft failed", false);
        assertTrue(decision.tagged());
        assertEquals("[noisy]", decision.tag());
        // The tag is not written twice when the rule already matches the tag.
        assertFalse(rules.decide("com.example.Plugin", "quiet", false).tagged());
    }

    @Test
    void ourOwnLoggerIsNeverFiltered() {
        LogRules rules = LogRules.build(config(rule("anything", "contains", "hide", "")), (a, b) -> {});
        assertFalse(rules.decide("dev.yanianz.atrium.module.homes.HomesModule", "anything broke", false).hide());
        assertTrue(rules.decide("com.example.Other", "anything broke", false).hide());
    }

    @Test
    void stackTracesStayUnlessErrorsAreHidden() {
        LogRules keep = LogRules.build(config(rule("boom", "contains", "hide", "")), (a, b) -> {});
        assertFalse(keep.decide("com.example.Other", "boom", true).hide());
        PerformanceConfig.ConsoleLog hiding = config(rule("boom", "contains", "hide", ""));
        hiding.hideErrors = true;
        assertTrue(LogRules.build(hiding, (a, b) -> {}).decide("com.example.Other", "boom", true).hide());
    }

    @Test
    void disabledRulesNeverMatch() {
        PerformanceConfig.ConsoleLog off = config(rule("x", "contains", "hide", ""));
        off.enabled = false;
        LogRules rules = LogRules.build(off, (a, b) -> {});
        assertFalse(rules.enabled());
        assertFalse(rules.decide("com.example.Other", "x", false).hide());
    }

    @Test
    void invalidEntriesAreReportedAndSkipped() {
        List<String> problems = new ArrayList<>();
        LogRules rules = LogRules.build(config(
                rule("", "contains", "hide", ""),
                rule("(", "regex", "hide", ""),
                rule("x", "glob", "hide", ""),
                rule("x", "contains", "explode", ""),
                rule("fine", "contains", "hide", "")), (key, reason) -> problems.add(key + ": " + reason));
        assertEquals(4, problems.size());
        assertEquals(1, rules.ruleCount());
    }

    @Test
    void loggerPrefixLimitsARule() {
        PerformanceConfig.ConsoleRule noisy = rule("saving", "contains", "hide", "");
        noisy.loggerPrefix = "com.example.Database";
        LogRules rules = LogRules.build(config(noisy), (a, b) -> {});
        assertTrue(rules.decide("com.example.Database.Pool", "saving chunk", false).hide());
        assertFalse(rules.decide("com.example.Other", "saving chunk", false).hide());
    }
}
