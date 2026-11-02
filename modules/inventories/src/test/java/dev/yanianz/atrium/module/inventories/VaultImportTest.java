package dev.yanianz.atrium.module.inventories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.inventories.VaultImportService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The slot layout the vault import promises to the importer, without a database or server registries.
 * {@code null} is an empty slot, so the fixtures only need nulls.
 */
class VaultImportTest {
    @Test
    void padsMissingSlotsAndKeepsPositions() {
        List<org.bukkit.inventory.ItemStack> items = new ArrayList<>();
        items.add(null);
        items.add(null);

        VaultImportService.Vault vault = new VaultImportService.Vault(1, 2, items);
        assertEquals(18, vault.items().size());
        assertEquals(18, vault.slots());
        for (int slot = 0; slot < 18; slot++) {
            assertNull(vault.items().get(slot));
        }
    }

    @Test
    void refusesItemsThatWouldNotFit() {
        List<org.bukkit.inventory.ItemStack> tooMany = new ArrayList<>();
        for (int i = 0; i < 19; i++) {
            tooMany.add(null);
        }
        assertThrows(IllegalArgumentException.class, () -> new VaultImportService.Vault(1, 2, tooMany));
        assertThrows(IllegalArgumentException.class, () -> new VaultImportService.Vault(0, 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new VaultImportService.Vault(1, 7, List.of()));
    }

    @Test
    void rowsFollowTheSlotCountAndStayWithinSix() {
        assertEquals(1, VaultImportWriter.rowsFor(9));
        assertEquals(2, VaultImportWriter.rowsFor(10));
        assertEquals(6, VaultImportWriter.rowsFor(54));
        assertEquals(6, VaultImportWriter.rowsFor(100));
        assertEquals(1, VaultImportWriter.rowsFor(0));
        assertTrue(VaultImportService.MAX_SLOTS == 54);
    }
}
