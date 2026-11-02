package dev.yanianz.atrium.module.inventories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class VaultLayoutTest {
    @Test
    void rowsAreClampedToChestSizes() {
        assertEquals(1, VaultLayout.rows(0));
        assertEquals(1, VaultLayout.rows(-5));
        assertEquals(3, VaultLayout.rows(3));
        assertEquals(6, VaultLayout.rows(99));
    }

    @Test
    void slotsAreRowsTimesNine() {
        assertEquals(9, VaultLayout.slots(1));
        assertEquals(54, VaultLayout.slots(6));
        assertEquals(54, VaultLayout.slots(42));
    }

    @Test
    void vaultCountIsClamped() {
        assertEquals(0, VaultLayout.vaultCount(-3));
        assertEquals(2, VaultLayout.vaultCount(2));
        assertEquals(VaultLayout.MAX_VAULTS, VaultLayout.vaultCount(9999));
        assertEquals(VaultLayout.MAX_VAULTS, VaultLayout.vaultCount(Integer.MAX_VALUE));
    }

    @Test
    void numbersMustBeInsideTheGrantedRange() {
        assertTrue(VaultLayout.validVault(1, 3));
        assertTrue(VaultLayout.validVault(3, 3));
        assertFalse(VaultLayout.validVault(4, 3));
        assertFalse(VaultLayout.validVault(0, 3));
        assertFalse(VaultLayout.validVault(-1, 3));
    }

    @Test
    void defaultVaultIsOneOrNothing() {
        assertEquals(1, VaultLayout.defaultVault(4));
        assertEquals(0, VaultLayout.defaultVault(0));
        assertEquals(0, VaultLayout.defaultVault(-1));
    }

    @Test
    void fitPadsWithEmptySlots() {
        VaultLayout.Fit<String> fit = VaultLayout.fit(List.of("a"), 3);
        assertEquals(java.util.Arrays.asList("a", null, null), fit.kept());
        assertTrue(fit.overflow().isEmpty());
    }

    @Test
    void fitReturnsItemsBeyondTheNewSize() {
        VaultLayout.Fit<String> fit = VaultLayout.fit(List.of("a", "b", "c", "d"), 2);
        assertEquals(List.of("a", "b"), fit.kept());
        assertEquals(List.of("c", "d"), fit.overflow());
    }

    @Test
    void fitSkipsEmptyOverflowSlots() {
        List<String> items = new ArrayList<>(List.of("a", "b"));
        items.add(null);
        items.add("d");
        VaultLayout.Fit<String> fit = VaultLayout.fit(items, 2);
        assertEquals(List.of("a", "b"), fit.kept());
        assertEquals(List.of("d"), fit.overflow(), "an empty trailing slot is not an item that must be returned");
    }

    @Test
    void fitKeepsPositionsAndAllowsNulls() {
        List<String> items = new ArrayList<>();
        items.add(null);
        items.add("x");
        VaultLayout.Fit<String> fit = VaultLayout.fit(items, 4);
        assertEquals(4, fit.kept().size());
        assertNull(fit.kept().get(0));
        assertEquals("x", fit.kept().get(1));
    }

    @Test
    void aZeroSizeKeepsEverythingAsOverflow() {
        VaultLayout.Fit<String> fit = VaultLayout.fit(List.of("a", "b"), 0);
        assertTrue(fit.kept().isEmpty());
        assertEquals(List.of("a", "b"), fit.overflow());
    }
}
