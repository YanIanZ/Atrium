package dev.yanianz.atrium.module.aliases;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AliasesTest {
    private static AliasesConfig.ArgumentDefinition arg(String name, String type, boolean required) {
        return AliasesConfig.ArgumentDefinition.of(name, type, required);
    }

    private static AliasSpec spec(AliasesConfig.ArgumentDefinition... args) {
        return AliasSpec.from(AliasesConfig.AliasDefinition.of("test", List.of("t"), "", "true", true, List.of(args),
                List.of("msg: hi")), lines -> List.of());
    }

    private static String specError(AliasesConfig.AliasDefinition def) {
        return assertThrows(IllegalArgumentException.class, () -> AliasSpec.from(def, lines -> List.of())).getMessage();
    }

    @Test void mapsArgumentsByIndexNameAndJoined() throws Exception {
        AliasSpec spec = spec(arg("target", "player", true), arg("amount", "integer", true), arg("reason", "text", false));
        Map<String, String> vars = ArgumentMapper.map(spec, List.of("Steve", "5", "being nice!"));
        assertEquals("Steve", vars.get("arg1"));
        assertEquals("Steve", vars.get("target"));
        assertEquals("5", vars.get("arg2"));
        assertEquals("5", vars.get("amount"));
        assertEquals("being nice!", vars.get("arg3"));
        assertEquals("being nice!", vars.get("reason"));
        assertEquals("Steve 5 being nice!", vars.get("args"));
    }

    @Test void missingOptionalArgumentsAreEmpty() throws Exception {
        AliasSpec spec = spec(arg("target", "player", true), arg("count", "integer", false));
        Map<String, String> vars = ArgumentMapper.map(spec, List.of("Alex"));
        assertEquals("", vars.get("arg2"));
        assertEquals("", vars.get("count"));
        assertEquals("Alex", vars.get("args"));
    }

    @Test void rejectsWrongCountAndInvalidValues() {
        AliasSpec spec = spec(arg("target", "player", true), arg("count", "integer", false));
        var missing = assertThrows(ArgumentMapper.InvalidArgumentException.class, () -> ArgumentMapper.map(spec, List.of()));
        assertEquals("target", missing.argument);
        assertThrows(ArgumentMapper.InvalidArgumentException.class, () -> ArgumentMapper.map(spec, List.of("a", "1", "x")));
        assertEquals("target", assertThrows(ArgumentMapper.InvalidArgumentException.class,
                () -> ArgumentMapper.map(spec, List.of("@a"))).argument);
        assertEquals("count", assertThrows(ArgumentMapper.InvalidArgumentException.class,
                () -> ArgumentMapper.map(spec, List.of("Steve", "1;2"))).argument);
    }

    @Test void argumentTypeValidation() {
        assertTrue(ArgumentMapper.valid(AliasSpec.ArgType.PLAYER, "Notch_99"));
        assertFalse(ArgumentMapper.valid(AliasSpec.ArgType.PLAYER, "@p"));
        assertFalse(ArgumentMapper.valid(AliasSpec.ArgType.PLAYER, "ThisNameIsWayTooLong"));
        assertTrue(ArgumentMapper.valid(AliasSpec.ArgType.WORD, "minecraft:stone"));
        assertFalse(ArgumentMapper.valid(AliasSpec.ArgType.WORD, "{uuid}"));
        assertFalse(ArgumentMapper.valid(AliasSpec.ArgType.WORD, "%player_name%"));
        assertTrue(ArgumentMapper.valid(AliasSpec.ArgType.INTEGER, "-12"));
        assertFalse(ArgumentMapper.valid(AliasSpec.ArgType.INTEGER, "1.5"));
        assertTrue(ArgumentMapper.valid(AliasSpec.ArgType.NUMBER, "1.5"));
        assertFalse(ArgumentMapper.valid(AliasSpec.ArgType.NUMBER, "1e9"));
        assertTrue(ArgumentMapper.valid(AliasSpec.ArgType.TEXT, "hello <red>world"));
        assertFalse(ArgumentMapper.valid(AliasSpec.ArgType.TEXT, "line\nbreak"));
        assertFalse(ArgumentMapper.valid(AliasSpec.ArgType.TEXT, "   "));
    }

    @Test void specValidation() {
        assertTrue(specError(AliasesConfig.AliasDefinition.of("Bad Name", List.of(), "", "true", true, List.of(), List.of("msg: x")))
                .contains("invalid name"));
        assertTrue(specError(AliasesConfig.AliasDefinition.of("a", List.of(), "", "maybe", true, List.of(), List.of("msg: x")))
                .contains("permission-default"));
        assertTrue(specError(AliasesConfig.AliasDefinition.of("a", List.of(), "", "true", true,
                List.of(arg("player", "word", true)), List.of("msg: x"))).contains("reserved"));
        assertTrue(specError(AliasesConfig.AliasDefinition.of("a", List.of(), "", "true", true,
                List.of(arg("arg1", "word", true)), List.of("msg: x"))).contains("reserved"));
        assertTrue(specError(AliasesConfig.AliasDefinition.of("a", List.of(), "", "true", true,
                List.of(arg("x1", "text", true), arg("y1", "word", true)), List.of("msg: x"))).contains("last"));
        assertTrue(specError(AliasesConfig.AliasDefinition.of("a", List.of(), "", "true", true,
                List.of(arg("x1", "word", false), arg("y1", "word", true)), List.of("msg: x"))).contains("before optional"));
        assertTrue(specError(AliasesConfig.AliasDefinition.of("a", List.of(), "", "true", true,
                List.of(arg("x1", "colour", true)), List.of("msg: x"))).contains("unknown type"));
        assertTrue(specError(AliasesConfig.AliasDefinition.of("a", List.of(), "", "true", true, List.of(), List.of()))
                .contains("no actions"));
        var def = AliasesConfig.AliasDefinition.of("a", List.of(), "", "true", true, List.of(), List.of("bad line"));
        String error = assertThrows(IllegalArgumentException.class,
                () -> AliasSpec.from(def, lines -> List.of("line 1: broken"))).getMessage();
        assertTrue(error.contains("actions line 1: broken"));
    }

    @Test void specDefaultsAndUsage() {
        AliasSpec spec = spec(arg("target", "player", true), arg("count", "integer", false));
        assertEquals("atrium.alias.test", spec.permission());
        assertEquals(List.of("t"), spec.aliases());
        assertEquals("/test <target> [count]", spec.usageLine());
        assertEquals(1, spec.requiredCount());
    }

    @Test void defaultConfigIsValid() {
        for (var def : new AliasesConfig().commands) {
            assertDoesNotThrow(() -> AliasSpec.from(def, lines -> List.of()), def.name);
        }
    }

    private static AliasesConfig.ReplacementRule rule(String from, String to, String permission) {
        var rule = new AliasesConfig.ReplacementRule();
        rule.from = from;
        rule.to = to;
        rule.permission = permission;
        return rule;
    }

    @Test void commandReplacementLongestPrefixWithPermission() {
        List<String> problems = new ArrayList<>();
        CommandRewriter rewriter = CommandRewriter.of(List.of(rule("/gm", "gamemode", ""), rule("gm 1", "gamemode creative", "vip"),
                rule("day", "time set day", ""), rule("", "x", ""), rule("loop", "/loop", "")), problems);
        assertEquals(2, problems.size());
        assertEquals(3, rewriter.size());
        Set<String> vip = Set.of("vip");
        assertEquals(Optional.of("/gamemode creative Steve"), rewriter.rewrite("/GM 1 Steve", vip::contains));
        assertEquals(Optional.of("/gamemode 1 Steve"), rewriter.rewrite("/gm 1 Steve", p -> false));
        assertEquals(Optional.of("/time set day"), rewriter.rewrite("/day", p -> false));
        assertEquals(Optional.empty(), rewriter.rewrite("/daylight", p -> false));
        assertEquals(Optional.empty(), rewriter.rewrite("/spawn", p -> true));
        assertEquals(Optional.empty(), rewriter.rewrite("day", p -> true));
    }

    private static AliasesConfig.ReplacementRule prefixRule(String from, String to) {
        AliasesConfig.ReplacementRule rule = rule(from, to, "");
        rule.prefixMatch = true;
        return rule;
    }

    private static AliasesConfig.ReplacementRule catchAllRule(String to) {
        AliasesConfig.ReplacementRule rule = rule("*", to, "");
        rule.catchAll = true;
        return rule;
    }

    @Test void prefixRuleMatchesPartialCommandName() {
        CommandRewriter rewriter = CommandRewriter.of(List.of(
                rule("home", "homes", ""), prefixRule("home", "home"), prefixRule("gm", "gamemode")),
                new ArrayList<>());
        // The whole-word rule beats the prefix rule for the exact spelling.
        assertEquals(Optional.of("/homes"), rewriter.rewrite("/home", p -> true));
        // The rest of the word is appended as a separate argument.
        assertEquals(Optional.of("/home base"), rewriter.rewrite("/homebase", p -> true));
        assertEquals(Optional.of("/gamemode 1 creative"), rewriter.rewrite("/gm1 creative", p -> true));
        assertEquals(Optional.empty(), rewriter.rewrite("/day", p -> true));
    }

    @Test void catchAllRunsOnlyWhenNothingElseMatched() {
        CommandRewriter rewriter = CommandRewriter.of(List.of(
                rule("day", "time set day", ""), catchAllRule("atrium help {args}")), new ArrayList<>());
        assertEquals(2, rewriter.size());
        assertEquals(Optional.of("/time set day"), rewriter.rewrite("/day", p -> true));
        assertEquals(Optional.of("/atrium help spawn Steve"), rewriter.rewrite("/spawn Steve", p -> true));
    }

    @Test void catchAllHonoursItsPermission() {
        AliasesConfig.ReplacementRule fallback = catchAllRule("help {args}");
        fallback.permission = "vip";
        CommandRewriter rewriter = CommandRewriter.of(List.of(fallback), new ArrayList<>());
        assertEquals(Optional.empty(), rewriter.rewrite("/spawn", p -> false));
        assertEquals(Optional.of("/help spawn"), rewriter.rewrite("/spawn", p -> true));
    }

    @Test void catchAllIsNotMatchedThroughTheNormalRules() {
        List<String> problems = new ArrayList<>();
        AliasesConfig.ReplacementRule emptyTo = catchAllRule("");
        CommandRewriter rewriter = CommandRewriter.of(List.of(emptyTo, catchAllRule("x")), problems);
        assertEquals(1, problems.size());
        assertEquals(1, rewriter.size());
    }
}
