package dev.yanianz.atrium.module.staff;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class InventoryMergeTest {
    private static final String TOOL = "tool";

    private static boolean isTool(String item) {
        return TOOL.equals(item);
    }

    @Test
    void clearKeepsOnlyTheSavedInventory() {
        InventoryMerge.Plan<String> plan = InventoryMerge.plan(
                new String[] {"saved", null, null}, new String[] {"new", TOOL, "more"}, InventoryMergeTest::isTool,
                ExtraItemsMode.CLEAR);
        assertArrayEquals(new String[] {"saved", null, null}, plan.target());
        assertTrue(plan.leftovers().isEmpty());
    }

    @Test
    void keepFillsEmptySlotsAndSkipsTools() {
        InventoryMerge.Plan<String> plan = InventoryMerge.plan(
                new String[] {"saved", null, null}, new String[] {"new", TOOL, "more"}, InventoryMergeTest::isTool,
                ExtraItemsMode.KEEP);
        assertArrayEquals(new String[] {"saved", "new", "more"}, plan.target());
        assertTrue(plan.leftovers().isEmpty());
    }

    @Test
    void keepDropsWhatDoesNotFit() {
        InventoryMerge.Plan<String> plan = InventoryMerge.plan(
                new String[] {"saved"}, new String[] {"a", "b", TOOL}, InventoryMergeTest::isTool, ExtraItemsMode.KEEP);
        assertArrayEquals(new String[] {"saved"}, plan.target());
        assertEquals(List.of("a", "b"), plan.leftovers());
    }

    @Test
    void dropMovesEveryNewItemToTheLeftovers() {
        InventoryMerge.Plan<String> plan = InventoryMerge.plan(
                new String[] {"saved", null}, new String[] {"a", TOOL}, InventoryMergeTest::isTool, ExtraItemsMode.DROP);
        assertArrayEquals(new String[] {"saved", null}, plan.target());
        assertEquals(List.of("a"), plan.leftovers());
    }

    @Test
    void nullSlotsAreTreatedAsEmpty() {
        InventoryMerge.Plan<String> plan = InventoryMerge.plan(
                new String[] {null}, new String[] {null}, InventoryMergeTest::isTool, ExtraItemsMode.KEEP);
        assertArrayEquals(new String[] {null}, plan.target());
        assertTrue(plan.leftovers().isEmpty());
    }

    @Test
    void largerSavedStorageSpillsIntoEmptySlotsBeforeDropping() {
        // Saved with 4 storage slots, restored where only 3 exist.
        InventoryMerge.Plan<String> plan = InventoryMerge.plan(new String[] {"a", null, "c", "d"}, 3, List.of(),
                new String[0], InventoryMergeTest::isTool, ExtraItemsMode.CLEAR);
        assertArrayEquals(new String[] {"a", "d", "c"}, plan.target());
        assertTrue(plan.leftovers().isEmpty());
    }

    @Test
    void savedOverflowWinsOverNewItemsAndTheRestIsDropped() {
        InventoryMerge.Plan<String> plan = InventoryMerge.plan(new String[] {"a", null, "c"}, 2,
                List.of("armour-extra"), new String[] {"new"}, InventoryMergeTest::isTool, ExtraItemsMode.KEEP);
        assertArrayEquals(new String[] {"a", "c"}, plan.target());
        assertEquals(List.of("armour-extra", "new"), plan.leftovers());
    }

    @Test
    void smallerSavedStorageIsPaddedToTheLiveSize() {
        InventoryMerge.Plan<String> plan = InventoryMerge.plan(new String[] {"a"}, 3, List.of(),
                new String[] {"new", TOOL}, InventoryMergeTest::isTool, ExtraItemsMode.KEEP);
        assertArrayEquals(new String[] {"a", "new", null}, plan.target());
        assertTrue(plan.leftovers().isEmpty());
    }
}
