package dev.yanianz.atrium.module.itemtools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class FrameActionTest {
    @Test
    void parsesEverySupportedActionCaseInsensitively() {
        for (FrameAction action : FrameAction.values()) {
            assertEquals(action, FrameAction.parse(action.id()).orElseThrow());
            assertEquals(action, FrameAction.parse(action.id().toUpperCase(Locale.ROOT)).orElseThrow());
            assertEquals(action, FrameAction.parse("  " + action.id() + " ").orElseThrow());
        }
    }

    @Test
    void rejectsUnknownOrMissingActions() {
        for (String input : List.of("toggle", "hide", "fixed false", "", "invisible!")) {
            assertTrue(FrameAction.parse(input).isEmpty(), "accepted invalid action: " + input);
        }
        assertTrue(FrameAction.parse(null).isEmpty());
    }

    @Test
    void namesAreStableForCommandsAndMessages() {
        assertEquals(List.of("invisible", "visible", "fixed", "unfixed"), FrameAction.names());
        assertFalse(FrameAction.names().isEmpty());
    }
}
