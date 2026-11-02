package dev.yanianz.atrium.module.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InventoryLayoutTest {
    @Test
    void enderViewMatchesTheLiveChest() {
        assertEquals(27, InventoryLayout.displaySize(true, 27));
        assertEquals(54, InventoryLayout.displaySize(true, 54));
        assertEquals(9, InventoryLayout.displaySize(true, 9));
        assertEquals(9, InventoryLayout.displaySize(true, 0));
        assertEquals(54, InventoryLayout.displaySize(true, 99));
    }

    @Test
    void playerViewKeepsFortyFiveSlotsWithInfo() {
        assertEquals(45, InventoryLayout.displaySize(false, 41));
        assertEquals(44, InventoryLayout.infoSlot(45, 41));
        assertEquals(41, InventoryLayout.shownSlots(45, 41));
        assertEquals(54, InventoryLayout.displaySize(false, 50));
        assertEquals(-1, InventoryLayout.infoSlot(54, 60));
    }

    @Test
    void editBoundsFollowTheLiveSize() {
        assertTrue(InventoryLayout.editable(53, 54, 54));
        assertFalse(InventoryLayout.editable(54, 54, 54));
        assertTrue(InventoryLayout.editable(26, 27, 27));
        assertFalse(InventoryLayout.editable(27, 27, 27));
        assertTrue(InventoryLayout.editable(40, 45, 41));
        assertFalse(InventoryLayout.editable(41, 45, 41));
        assertFalse(InventoryLayout.editable(44, 45, 41));
        assertFalse(InventoryLayout.editable(-1, 45, 41));
    }

    @Test
    void sixRowSnapshotIsPaged() {
        assertEquals(4, InventoryLayout.rows(27));
        assertEquals(6, InventoryLayout.rows(41));
        assertEquals(6, InventoryLayout.rows(54));
        assertEquals(1, InventoryLayout.pages(41));
        assertEquals(2, InventoryLayout.pages(54));
        assertEquals(45, InventoryLayout.count(0, 54));
        assertEquals(45, InventoryLayout.first(1, 54));
        assertEquals(9, InventoryLayout.count(1, 54));
        assertEquals(45, InventoryLayout.first(5, 54));
    }
}
