package dev.yanianz.atrium.module.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Regression: /enderchest stays reachable through /endersee and /ec. */
class InventoryAliasesTest {
    @Test
    void enderChestRegistersTheOfflineFriendlyAliases() {
        assertEquals(List.of("endersee", "ec"), InventoryViewer.enderAliases());
    }

    @Test
    void aliasesAreUniqueAndDoNotShadowTheRootCommand() {
        List<String> aliases = InventoryViewer.enderAliases();
        assertEquals(aliases.size(), new HashSet<>(aliases).size());
        assertTrue(aliases.stream().noneMatch("enderchest"::equalsIgnoreCase));
        assertTrue(aliases.stream().allMatch(alias -> alias.matches("[a-z]{2,16}")));
    }
}
