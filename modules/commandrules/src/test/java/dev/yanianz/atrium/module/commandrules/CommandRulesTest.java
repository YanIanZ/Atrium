package dev.yanianz.atrium.module.commandrules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class CommandRulesTest {
    private static final Map<String, String> ALIASES = Map.of(
            "tp", "tp",
            "teleport", "tp",
            "minecraft:tp", "tp",
            "atrium:fly", "fly",
            "fly", "fly");

    private static final Function<String, String> RESOLVER = ALIASES::get;

    private static CommandRulesConfig.Rule rule(String command, double cost, int levels, int cooldown, int warmup,
            List<String> worlds, String permission, boolean confirm) {
        return CommandRulesConfig.Rule.of(command, cost, levels, cooldown, warmup, worlds, permission, confirm);
    }

    private static CommandRulesConfig.Rule rule(String command, double cost, int levels, int cooldown, int warmup,
            List<String> worlds, String permission, boolean confirm, List<String> aliases) {
        return CommandRulesConfig.Rule.of(command, cost, levels, cooldown, warmup, worlds, permission, confirm, aliases);
    }

    @Test
    void aliasIndexFindsAliasCopiesRedirectsAndNamespaces() {
        Object balance = new Object();
        List<CommandAliases.Known> known = List.of(
                // The main wrapper declares the aliases; Paper's alias copies have none.
                new CommandAliases.Known("balance", "balance", List.of("bal", "atrium:bal"), balance),
                new CommandAliases.Known("bal", "bal", List.of(), null),
                new CommandAliases.Known("atrium:bal", "bal", List.of(), null),
                new CommandAliases.Known("tp", "tp", List.of(), null),
                new CommandAliases.Known("teleport", "teleport", List.of(), null),
                new CommandAliases.Known("minecraft:tp", "tp", List.of(), null));
        CommandAliases aliases = CommandAliases.build(known, Map.of("tp", "teleport"));

        assertEquals("balance", aliases.resolve("bal"));
        assertEquals("balance", aliases.resolve("atrium:bal"));
        assertEquals("balance", aliases.resolve(CommandLabels.root("/BAL")));
        assertEquals("teleport", aliases.resolve("minecraft:tp"));
        assertEquals("teleport", aliases.resolve(CommandLabels.root("/TP")));
        assertEquals("teleport", aliases.resolve("teleport"));
        assertEquals("unknown", aliases.resolve("unknown"));
    }

    @Test
    void aliasIndexHandlesCommandsSharingOneCommandObject() {
        Object home = new Object();
        CommandAliases aliases = CommandAliases.build(List.of(
                new CommandAliases.Known("h", "home", List.of(), home),
                new CommandAliases.Known("home", "home", List.of(), home)), Map.of());
        assertEquals("home", aliases.resolve("h"));
        assertEquals("home", aliases.resolve("home"));
    }

    @Test
    void ruleAliasesCoverCommandsTheCommandMapCannotResolve() {
        List<String> warnings = new ArrayList<>();
        RuleSet rules = RuleSet.build(List.of(
                rule("home", 10, 0, 0, 0, List.of(), "", false, List.of("base", "h"))),
                label -> null, warnings::add);

        assertEquals("home", rules.find("base").orElseThrow().root());
        assertEquals("home", rules.find("h").orElseThrow().root());
        assertEquals("home", rules.find("base", label -> null).orElseThrow().root());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void labelCanonicalisationHandlesSlashesCaseNamespacesAndAliases() {
        assertEquals("teleport", CommandLabels.root("  /Teleport Steve  "));
        assertEquals("tp", CommandLabels.root("//tp Alex"));
        assertEquals("", CommandLabels.root("   "));

        assertEquals("tp", CommandLabels.canonicalize("/Teleport Steve", RESOLVER));
        assertEquals("tp", CommandLabels.canonicalize("minecraft:tp Alex", RESOLVER));
        assertEquals("fly", CommandLabels.canonicalize("/atrium:fly", RESOLVER));
        // Unknown commands still lose the namespace, so a later registration matches the same key.
        assertEquals("home", CommandLabels.canonicalize("minecraft:home", RESOLVER));
        // A command map that reports the namespaced name as its canonical name is normalised too.
        assertEquals("tp", CommandLabels.canonicalize("minecraft:tp", label -> "minecraft:tp"));
        assertEquals("tp", CommandLabels.stripNamespace("minecraft:tp"));
        assertEquals("tp", CommandLabels.stripNamespace("tp"));
    }

    @Test
    void rulesMatchCanonicalRootsAndRejectDuplicates() {
        List<String> warnings = new ArrayList<>();
        RuleSet rules = RuleSet.build(List.of(
                rule("teleport", 100, 2, 30, 3, List.of("World_Nether"), "atrium.test", true),
                rule("tp", 5, 0, 0, 0, List.of(), "", false),
                rule("fly", -4, -2, -5, -1, List.of(" "), "", false),
                rule("", 0, 0, 0, 0, List.of(), "", false),
                rule("walk", 0, 0, 0, 0, List.of(), "", false)), RESOLVER, warnings::add);

        assertEquals(3, rules.size());
        CommandRule tp = rules.find("tp").orElseThrow();
        assertEquals("tp", tp.root());
        assertEquals(100, tp.cost().intValue());
        assertEquals(2, tp.expLevels());
        assertEquals(30, tp.cooldownSeconds());
        assertEquals(3, tp.warmupSeconds());
        assertEquals("atrium.test", tp.permission());
        assertTrue(tp.confirmCost());
        assertTrue(tp.blocksWorld("world_nether"));
        assertTrue(tp.blocksWorld("WORLD_NETHER"));
        assertFalse(tp.blocksWorld("world"));

        CommandRule fly = rules.find("fly").orElseThrow();
        assertEquals(0, fly.cost().signum());
        assertEquals(0, fly.expLevels());
        assertEquals(0, fly.cooldownSeconds());
        assertEquals(0, fly.warmupSeconds());
        assertTrue(fly.blockedWorlds().isEmpty());

        assertTrue(rules.find("walk").isPresent());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("duplicate")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("negative cost")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("invalid cooldown")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("empty 'command'")));
    }

    @Test
    void ruleResolvesAliasAfterCommandMapBecomesAvailable() {
        // Simulates a cold start where the rule is loaded before the command map knows 'teleport'.
        Function<String, String> emptyMap = label -> null;
        RuleSet loadedEarly = RuleSet.build(List.of(rule("teleport", 10, 0, 0, 0, List.of(), "", false)),
                emptyMap, problem -> { });
        assertTrue(loadedEarly.find("tp").isEmpty());
        assertTrue(loadedEarly.find("tp", RESOLVER).isPresent());
        assertEquals("teleport", loadedEarly.find("tp", RESOLVER).orElseThrow().root());
    }

    @Test
    void cooldownPermissionNumberCanRaiseOrLowerTheConfiguredValue() {
        assertEquals(30, CooldownPolicy.effectiveSeconds(30, OptionalInt.empty()));
        assertEquals(5, CooldownPolicy.effectiveSeconds(30, OptionalInt.of(5)));
        assertEquals(90, CooldownPolicy.effectiveSeconds(30, OptionalInt.of(90)));
        assertEquals(0, CooldownPolicy.effectiveSeconds(30, OptionalInt.of(0)));
        // A configured 0 means "no cooldown"; a permission must not invent one.
        assertEquals(0, CooldownPolicy.effectiveSeconds(0, OptionalInt.of(5)));
        // A lifetime cooldown can be replaced by a finite permission override.
        assertEquals(10, CooldownPolicy.effectiveSeconds(-1, OptionalInt.of(10)));
        assertEquals(-1, CooldownPolicy.effectiveSeconds(-1, OptionalInt.empty()));

        assertEquals(Duration.ZERO, CooldownPolicy.duration(0));
        assertEquals(Duration.ofSeconds(12), CooldownPolicy.duration(12));
        assertEquals(CooldownPolicy.LIFETIME, CooldownPolicy.duration(-1));
        assertEquals("none", CooldownPolicy.display(0, "none", "lifetime"));
        assertEquals("lifetime", CooldownPolicy.display(-1, "none", "lifetime"));
        assertEquals("15s", CooldownPolicy.display(15, "none", "lifetime"));
    }

    @Test
    void warmupStateMachineCompletesExactlyAtZeroAndNeverRevives() {
        WarmupState state = WarmupState.start(3);
        assertTrue(state.active());
        assertEquals(3, state.remainingSeconds());

        state = state.tick();
        assertEquals(2, state.remainingSeconds());
        assertTrue(state.active());

        state = state.tick();
        assertEquals(1, state.remainingSeconds());
        assertTrue(state.active());

        state = state.tick();
        assertEquals(0, state.remainingSeconds());
        assertEquals(WarmupState.Phase.COMPLETED, state.phase());
        assertFalse(state.active());

        assertEquals(state, state.tick(), "a completed warmup must not run again");
        assertEquals(WarmupState.Phase.COMPLETED, state.cancel().phase());
        assertThrows(IllegalArgumentException.class, () -> WarmupState.start(0));
    }

    @Test
    void warmupStateCancelsOnlyWhileActive() {
        WarmupState active = WarmupState.start(2);
        WarmupState cancelled = active.cancel();
        assertEquals(WarmupState.Phase.CANCELLED, cancelled.phase());
        assertFalse(cancelled.active());
        assertEquals(cancelled, cancelled.tick());
        assertEquals(WarmupState.Phase.CANCELLED, cancelled.cancel().phase());
    }
}
