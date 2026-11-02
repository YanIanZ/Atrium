package dev.yanianz.atrium.module.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** New automatic behaviour must stay off unless the server owner enables it. */
class CombatConfigTest {
    @Test
    void automaticPunishmentAndDamageRulesStartDisabled() {
        CombatConfig config = new CombatConfig();
        assertFalse(config.damageControl.enabled);
        assertFalse(config.logout.enabled);
        assertFalse(config.logout.dropExperience);
        assertTrue(config.notarget.enabled);
        assertTrue(config.worldEnabled("world"));
    }

    @Test
    void durationsAreClamped() {
        CombatConfig.Tag tag = new CombatConfig.Tag();
        tag.seconds = 0;
        assertEquals(1, tag.seconds());
        tag.seconds = 10_000;
        assertEquals(600, tag.seconds());
        tag.mobSeconds = -4;
        assertEquals(1, tag.mobSeconds());
    }

    @Test
    void logoutModeFallsBackToDropAndReportsInvalidValues() {
        CombatConfig.Logout logout = new CombatConfig.Logout();
        assertEquals(CombatConfig.Logout.LogoutMode.DROP, logout.mode());
        logout.mode = "kill";
        assertEquals(CombatConfig.Logout.LogoutMode.KILL, logout.mode());
        logout.mode = "banana";
        assertEquals(CombatConfig.Logout.LogoutMode.DROP, logout.mode());
        assertNull(logout.parsedMode());
    }

    @Test
    void unknownBossBarColourFallsBackToRed() {
        CombatConfig.Tag tag = new CombatConfig.Tag();
        assertEquals(net.kyori.adventure.bossbar.BossBar.Color.RED, tag.resolvedBossBarColor());
        tag.bossBarColor = "green";
        assertEquals(net.kyori.adventure.bossbar.BossBar.Color.GREEN, tag.resolvedBossBarColor());
        tag.bossBarColor = "colourless";
        assertEquals(null, tag.bossBarColor());
        assertEquals(net.kyori.adventure.bossbar.BossBar.Color.RED, tag.resolvedBossBarColor());
    }

    @Test
    void validationReportsConfigurationMistakes() {
        CombatConfig config = new CombatConfig();
        config.tag.seconds = 0;
        config.tag.display = "chat";
        config.tag.bossBarColor = "colourless";
        config.logout.mode = "banana";
        CombatConfig.DamageRule broken = new CombatConfig.DamageRule();
        broken.cause = "NOT_A_CAUSE";
        broken.multiplier = -1;
        broken.permissions = new ArrayList<>(List.of(" "));
        broken.worlds = new ArrayList<>(List.of(""));
        config.damageControl.rules = new ArrayList<>(List.of(broken));

        List<String> warnings = new ArrayList<>();
        config.validate(warnings::add);

        assertTrue(warnings.stream().anyMatch(line -> line.startsWith("tag.seconds")));
        assertTrue(warnings.stream().anyMatch(line -> line.startsWith("tag.display")));
        assertTrue(warnings.stream().anyMatch(line -> line.startsWith("tag.boss-bar-color")));
        assertTrue(warnings.stream().anyMatch(line -> line.startsWith("logout.mode")));
        assertTrue(warnings.stream().anyMatch(line -> line.contains("unknown damage cause")));
        assertTrue(warnings.stream().anyMatch(line -> line.contains("multiplier clamped")));
        assertTrue(warnings.stream().anyMatch(line -> line.contains("blank permission")));
        assertTrue(warnings.stream().anyMatch(line -> line.contains("blank world")));
    }

    @Test
    void aCleanConfigProducesNoWarnings() {
        CombatConfig config = new CombatConfig();
        List<String> warnings = new ArrayList<>();
        config.validate(warnings::add);
        assertTrue(warnings.isEmpty(), () -> "unexpected warnings: " + warnings);
    }
}
