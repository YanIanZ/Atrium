package dev.yanianz.atrium.module.inventories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RestorePlanTest {
    private static final RestorePlan.Sizes VANILLA = new RestorePlan.Sizes(36, 4, 1, 27);

    private static List<String> slots(int size) {
        List<String> items = new ArrayList<>(size);
        for (int slot = 0; slot < size; slot++) {
            items.add(null);
        }
        return items;
    }

    private static boolean empty(String item) {
        return item.isEmpty();
    }

    @Test
    void sixRowSnapshotIntoThreeRowChestOverflowsOnlyRealItems() {
        List<String> ender = slots(54);
        ender.set(0, "kept");
        ender.set(26, "last-kept");
        ender.set(27, "spill-a");
        ender.set(40, "");
        ender.set(53, "spill-b");
        RestorePlan.Plan<String> plan = RestorePlan.plan(
                new SnapshotCodec.Contents<>(slots(41), ender), VANILLA, RestorePlanTest::empty);
        assertEquals(27, plan.ender().size());
        assertEquals("kept", plan.ender().get(0));
        assertEquals("last-kept", plan.ender().get(26));
        assertEquals(List.of("spill-a", "spill-b"), plan.overflow());
        assertTrue(plan.overflows());
    }

    @Test
    void sameSizesNeverOverflow() {
        List<String> inventory = slots(41);
        inventory.set(0, "sword");
        inventory.set(39, "helmet");
        inventory.set(40, "shield");
        List<String> ender = slots(27);
        ender.set(26, "pearl");
        RestorePlan.Plan<String> plan = RestorePlan.plan(
                new SnapshotCodec.Contents<>(inventory, ender), VANILLA, RestorePlanTest::empty);
        assertFalse(plan.overflows());
        assertEquals("sword", plan.storage().get(0));
        assertEquals("helmet", plan.armour().get(3));
        assertEquals(List.of("shield"), plan.offhand());
        assertEquals("pearl", plan.ender().get(26));
    }

    @Test
    void smallerSnapshotIsPaddedToTheLiveSize() {
        List<String> ender = slots(27);
        ender.set(3, "gold");
        RestorePlan.Plan<String> plan = RestorePlan.plan(new SnapshotCodec.Contents<>(slots(41), ender),
                new RestorePlan.Sizes(36, 4, 1, 54), RestorePlanTest::empty);
        assertEquals(54, plan.ender().size());
        assertEquals("gold", plan.ender().get(3));
        assertNull(plan.ender().get(53));
        assertFalse(plan.overflows());
    }

    @Test
    void largerStorageOverflowsInSectionOrder() {
        List<String> inventory = slots(45 + SnapshotCodec.EQUIPMENT_SLOTS);
        inventory.set(40, "storage-extra");
        inventory.set(45, "boots");
        inventory.set(49, "shield");
        List<String> ender = slots(54);
        ender.set(50, "ender-extra");
        RestorePlan.Plan<String> plan = RestorePlan.plan(new SnapshotCodec.Contents<>(inventory, ender),
                VANILLA, RestorePlanTest::empty);
        assertEquals(36, plan.storage().size());
        assertEquals("boots", plan.armour().get(0));
        assertEquals(List.of("shield"), plan.offhand());
        assertEquals(List.of("storage-extra", "ender-extra"), plan.overflow());
    }

    @Test
    void missingLiveSectionsOverflowEverything() {
        List<String> inventory = slots(41);
        inventory.set(36, "boots");
        inventory.set(40, "shield");
        RestorePlan.Plan<String> plan = RestorePlan.plan(new SnapshotCodec.Contents<>(inventory, slots(27)),
                new RestorePlan.Sizes(36, 0, 0, 27), RestorePlanTest::empty);
        assertTrue(plan.armour().isEmpty());
        assertTrue(plan.offhand().isEmpty());
        assertEquals(List.of("boots", "shield"), plan.overflow());
    }
}
