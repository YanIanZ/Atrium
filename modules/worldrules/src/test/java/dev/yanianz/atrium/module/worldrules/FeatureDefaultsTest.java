package dev.yanianz.atrium.module.worldrules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** New automatic behaviour must stay off unless the server owner enables it. */
class FeatureDefaultsTest {
    @Test
    void everyAutomaticFeatureStartsDisabled() {
        WorldRulesConfig config = new WorldRulesConfig();
        assertFalse(config.death.autoRespawn);
        assertFalse(config.death.keepInventoryPermission);
        assertFalse(config.death.keepExperiencePermission);
        assertFalse(config.voidRescue.enabled);
        assertFalse(config.sleeping.blockCommands);
        assertFalse(config.sleeping.speedUp.enabled);
        assertFalse(config.resurrection.enabled);
        assertFalse(config.compass.bossBar);
        assertFalse(config.compass.recoverOnDeath);
        assertTrue(config.compass.requireItem);
        assertFalse(config.leatherAnimation.enabled);
        assertFalse(config.timeRate.enabled);
    }

    @Test
    void worldFlagsDefaultToVanilla() {
        WorldRule rule = new WorldRule();
        assertEquals(WorldRule.FlightMode.DEFAULT, rule.flightMode());
        assertEquals(WorldRule.GodMode.DEFAULT, rule.godMode());
        assertEquals(0, rule.flyAboveRoofHeight());
        assertEquals(1.0, rule.dayTimeMultiplier());
        assertEquals(1.0, rule.nightTimeMultiplier());
        assertFalse(rule.disableEnderPearls);
        assertFalse(rule.disableItemDrop);
        assertFalse(rule.disableItemPickup);
        assertFalse(rule.disableFireSpread);
        assertFalse(rule.disableExplosionBlockDamage);
        assertFalse(rule.disableLeafDecay);
        assertFalse(rule.disableCropTrample);
        assertFalse(rule.disablePortalCreation);
        assertFalse(rule.blockSpawnerMobs);
        assertTrue(rule.blockMobSpawns.isEmpty());
    }

    @Test
    void worldFlagsParseAndClamp() {
        WorldRule rule = new WorldRule();
        rule.fly = "allow";
        assertEquals(WorldRule.FlightMode.ALLOW, rule.flightMode());
        rule.fly = "nonsense";
        assertEquals(WorldRule.FlightMode.DEFAULT, rule.flightMode());
        rule.godMode = "on";
        assertEquals(WorldRule.GodMode.ON, rule.godMode());
        rule.flyAboveRoofHeight = -5;
        assertEquals(0, rule.flyAboveRoofHeight());
        rule.dayTimeMultiplier = 500;
        assertEquals(100.0, rule.dayTimeMultiplier());
        rule.nightTimeMultiplier = Double.NaN;
        assertEquals(1.0, rule.nightTimeMultiplier());
    }

    @Test
    void deathDropPercentIsClamped() {
        WorldRule rule = new WorldRule();
        assertEquals(100.0, rule.deathDropPercent());
        rule.deathDropPercent = -5;
        assertEquals(0.0, rule.deathDropPercent());
        rule.deathDropPercent = 250;
        assertEquals(100.0, rule.deathDropPercent());
        rule.deathDropPercent = 40;
        assertEquals(40.0, rule.deathDropPercent());
    }

    @Test
    void speedUpClampsItsRange() {
        WorldRulesConfig.SpeedUp speedUp = new WorldRulesConfig.SpeedUp();
        speedUp.requiredPercent = 0;
        assertEquals(0.1, speedUp.requiredPercent());
        speedUp.requiredPercent = 5;
        assertEquals(1.0, speedUp.requiredPercent());
        speedUp.ticksPerSecond = 0;
        assertEquals(1.0, speedUp.ticksPerSecond());
        speedUp.ticksPerSecond = 99999;
        assertEquals(1000.0, speedUp.ticksPerSecond());
    }

    @Test
    void resurrectionValuesAreClamped() {
        WorldRulesConfig.Resurrection resurrection = new WorldRulesConfig.Resurrection();
        resurrection.warmupSeconds = -1;
        assertEquals(0, resurrection.warmupSeconds());
        resurrection.cooldownSeconds = 999999;
        assertEquals(86400, resurrection.cooldownSeconds());
        resurrection.health = 0;
        assertEquals(0.5, resurrection.health());
        resurrection.health = 999;
        assertEquals(20.0, resurrection.health());
    }

    @Test
    void voidRescueValuesAreClamped() {
        WorldRulesConfig.VoidRescue rescue = new WorldRulesConfig.VoidRescue();
        rescue.triggerOffset = -10;
        assertEquals(0, rescue.triggerOffset());
        rescue.triggerOffset = 999;
        assertEquals(256, rescue.triggerOffset());
        rescue.cooldownSeconds = -1;
        assertEquals(0, rescue.cooldownSeconds());
        rescue.cooldownSeconds = 9999;
        assertEquals(300, rescue.cooldownSeconds());
    }
}
