package dev.yanianz.atrium.module.holograms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LineClickTest {
    @Test
    void aLeadingSlashIsDropped() {
        assertEquals("spawn", HologramCommands.validateClick("/spawn"));
        assertEquals("give {player} diamond 1", HologramCommands.validateClick("  /give {player} diamond 1  "));
    }

    @Test
    void onePrintableLineOnly() {
        assertThrows(IllegalArgumentException.class, () -> HologramCommands.validateClick("say one\nsay two"));
        assertThrows(IllegalArgumentException.class, () -> HologramCommands.validateClick("say\u00a7cred"));
        assertThrows(IllegalArgumentException.class, () -> HologramCommands.validateClick("   "));
        assertThrows(IllegalArgumentException.class, () -> HologramCommands.validateClick("/"));
    }

    @Test
    void veryLongCommandsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> HologramCommands.validateClick("x".repeat(257)));
        assertEquals(256, HologramCommands.validateClick("y".repeat(256)).length());
    }
}
