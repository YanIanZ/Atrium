package dev.yanianz.atrium.module.worldrules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class WorldRulesTest {
    @Test
    void exactWorldWinsOverWildcardAndMissingWorldUsesVanilla() {
        WorldRule exact = new WorldRule();
        exact.disablePvp = true;
        WorldRule wildcard = new WorldRule();
        wildcard.disableElytra = true;
        Map<String, WorldRule> worlds = Map.of("arena", exact, "*", wildcard);

        assertTrue(WorldRules.resolve(worlds, "arena").disablePvp);
        assertFalse(WorldRules.resolve(worlds, "arena").disableElytra);
        assertTrue(WorldRules.resolve(worlds, "other").disableElytra);
        assertFalse(WorldRules.resolve(Map.of(), "other").disableElytra);
    }

    @Test
    void fallDamageMultiplierAndDisable() {
        WorldRule rule = new WorldRule();
        rule.fallDamageMultiplier = 0.5;
        assertEquals(5.0, WorldRules.fallDamage(10.0, rule));
        rule.disableFallDamage = true;
        assertEquals(0.0, WorldRules.fallDamage(10.0, rule));
        rule.disableFallDamage = false;
        rule.fallDamageMultiplier = Double.NaN;
        assertEquals(10.0, WorldRules.fallDamage(10.0, rule));
    }

    @Test
    void hungerMultiplierCarriesFractionsBetweenEvents() {
        WorldRule rule = new WorldRule();
        rule.hungerMultiplier = 0.5;

        WorldRules.HungerResult first = WorldRules.hunger(20, 19, rule, 0);
        assertEquals(20, first.foodLevel());
        assertEquals(0.5, first.carry(), 0.0001);

        WorldRules.HungerResult second = WorldRules.hunger(20, 19, rule, first.carry());
        assertEquals(19, second.foodLevel());
        assertEquals(0.0, second.carry(), 0.0001);
    }

    @Test
    void hungerCanBeDisabledButEatingIsNeverBlocked() {
        WorldRule rule = new WorldRule();
        rule.disableHunger = true;

        assertEquals(20, WorldRules.hunger(20, 19, rule, 0).foodLevel());
        assertEquals(15, WorldRules.hunger(10, 15, rule, 0).foodLevel());
        assertEquals(0, WorldRules.hunger(10, 15, rule, 0).carry(), 0.0001);
    }

    @Test
    void hungerMultiplierAboveOneDrainsFaster() {
        WorldRule rule = new WorldRule();
        rule.hungerMultiplier = 2.0;
        assertEquals(18, WorldRules.hunger(20, 19, rule, 0).foodLevel());
        assertEquals(0, WorldRules.hunger(1, 0, rule, 0).foodLevel());
    }

    @Test
    void gatewayTargetParsingRejectsMalformedAndNonFiniteValues() {
        WorldRules.GatewayTarget target = WorldRules.parseGatewayTarget("world_nether, 0, 64, -20, 90, 0");
        assertEquals("world_nether", target.world());
        assertEquals(-20, target.z(), 0.0001);

        assertNull(WorldRules.parseGatewayTarget(""));
        assertNull(WorldRules.parseGatewayTarget("world_nether, 0, 64"));
        assertNull(WorldRules.parseGatewayTarget("world, NaN, 64, 0"));
        assertNull(WorldRules.parseGatewayTarget(", 0, 64, 0"));
    }

    @Test
    void configClampsLooseValues() {
        WorldRulesConfig config = new WorldRulesConfig();
        config.compassRefreshTicks = 1;
        config.maxCompassTrackers = 0;
        config.blockCycleReach = 999;
        assertEquals(5, config.compassRefreshTicks());
        assertEquals(1, config.maxCompassTrackers());
        assertEquals(50, config.blockCycleReach());
    }

    @Test
    void forcedGameModeAcceptsNamesAndNumbersButNotGarbage() {
        WorldRule rule = new WorldRule();
        rule.forceGameMode = "creative";
        assertEquals(org.bukkit.GameMode.CREATIVE, rule.forcedGameMode());
        rule.forceGameMode = "2";
        assertEquals(org.bukkit.GameMode.ADVENTURE, rule.forcedGameMode());
        rule.forceGameMode = "not-a-mode";
        assertNull(rule.forcedGameMode());
    }
}
