package dev.yanianz.atrium.module.player;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CommandDescriptionsTest {
    @Test
    void everyCommandOfTheModuleIsDescribed() {
        for (String command : List.of("feed", "god", "extinguish", "suicide", "hat", "lightning", "gamemode",
                "gmc", "gms", "gma", "gmsp", "speed", "repair", "burn", "exp", "workbench", "anvil", "grindstone",
                "stonecutter", "loom", "cartographytable", "smithingtable", "clear", "near", "skull", "more",
                "itemname", "lore", "enderchest", "invsee")) {
            String description = CommandDescriptions.of(command);
            assertFalse(description.isBlank(), command);
            assertFalse(description.startsWith("Player utility"), command);
            assertTrue(Character.isUpperCase(description.charAt(0)), command);
            assertFalse(description.endsWith("."), command);
        }
    }

    @Test
    void unknownCommandsFailFast() {
        assertThrows(IllegalArgumentException.class, () -> CommandDescriptions.of("nonexistent"));
    }
}
