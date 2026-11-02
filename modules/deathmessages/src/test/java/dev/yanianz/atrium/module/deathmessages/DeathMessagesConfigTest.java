package dev.yanianz.atrium.module.deathmessages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DeathMessagesConfigTest {
    @Test
    void worldMapOverridesTheGlobalSwitch() {
        DeathMessagesConfig config = new DeathMessagesConfig();
        config.enabled = false;
        config.worlds.put("nether", true);
        assertTrue(config.enabledIn("nether"));
        assertFalse(config.enabledIn("world"));
    }

    @Test
    void firstConfiguredTemplateWins() {
        DeathMessagesConfig config = new DeathMessagesConfig();
        assertTrue(config.templateFor(List.of("player", "fallback")).contains("<killer>"));
        config.templates.remove("player");
        assertEquals("<victim> <gray>died", config.templateFor(List.of("player", "fallback")));
    }

    @Test
    void blankTemplatesAreSkipped() {
        DeathMessagesConfig config = new DeathMessagesConfig();
        config.templates.put("fall", " ");
        assertTrue(config.templateFor(List.of("fall", "fallback")).contains("died"));
    }
}
