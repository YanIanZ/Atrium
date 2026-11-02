package dev.yanianz.atrium.module.inventories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression: the contributed-stack diff must follow item identity and total amount, so moving or splitting
 * stacks never looks like a new item and a real addition is never lost.
 */
class StackDeltaTest {
    /** Minimal slot for the pure diff: an identity key plus an amount. */
    private record Slot(String key, int amount) {}

    private static final StackDelta.SlotView<Slot> VIEW = new StackDelta.SlotView<>() {
        @Override
        public boolean empty(Slot slot) {
            return slot == null || slot.amount() <= 0;
        }

        @Override
        public String key(Slot slot) {
            return slot.key();
        }

        @Override
        public int amount(Slot slot) {
            return slot.amount();
        }
    };

    private static StackDelta.Diff diff(List<Slot> base, List<Slot> edited) {
        return StackDelta.of(base, edited, VIEW);
    }

    @Test
    void identicalContentsHaveNoDelta() {
        List<Slot> contents = List.of(new Slot("stone", 64), new Slot("sword", 1));
        StackDelta.Diff delta = diff(contents, contents);
        assertTrue(delta.isEmpty());
        assertEquals(0, delta.addedItems());
        assertEquals(0, delta.removedItems());
    }

    @Test
    void movingAStackIsNotAnAddition() {
        StackDelta.Diff delta = diff(List.of(new Slot("stone", 64)), List.of(new Slot("stone", 64)));
        assertTrue(delta.added().isEmpty());
        assertTrue(delta.removed().isEmpty());
    }

    @Test
    void splittingAStackIsNotAnAddition() {
        StackDelta.Diff delta = diff(List.of(new Slot("stone", 64)),
                List.of(new Slot("stone", 32), new Slot("stone", 32)));
        assertTrue(delta.added().isEmpty());
        assertTrue(delta.removed().isEmpty());
    }

    @Test
    void aPartialStackOnTopOfTheBaseCopyIsAdded() {
        StackDelta.Diff delta = diff(List.of(new Slot("stone", 64)),
                List.of(new Slot("stone", 64), new Slot("stone", 12)));
        assertEquals(12, delta.addedItems());
        assertEquals(12, delta.added().get("stone"));
        assertTrue(delta.removed().isEmpty());
    }

    @Test
    void removedStacksAreReportedSeparatelyAndNeverCountAsAdded() {
        StackDelta.Diff delta = diff(List.of(new Slot("dirt", 5), new Slot("stone", 10)), List.of());
        assertEquals(0, delta.addedItems());
        assertEquals(15, delta.removedItems());
        assertEquals(5, delta.removed().get("dirt"));
    }

    @Test
    void differentItemsWithTheSameAmountAreNotNetted() {
        StackDelta.Diff delta = diff(List.of(new Slot("stone", 1)), List.of(new Slot("diamond", 1)));
        assertEquals(1, delta.added().get("diamond"));
        assertEquals(1, delta.removed().get("stone"));
    }

    @Test
    void amountsOfTheSameItemAreSummedAcrossSlots() {
        StackDelta.Diff delta = diff(List.of(new Slot("stone", 10), new Slot("stone", 20)),
                List.of(new Slot("stone", 25)));
        assertEquals(0, delta.addedItems());
        assertEquals(5, delta.removed().get("stone"));
    }

    @Test
    void emptyAndInvalidSlotsAreIgnored() {
        StackDelta.Diff delta = diff(java.util.Arrays.asList(new Slot("stone", 0), null),
                List.of(new Slot("stone", -3)));
        assertTrue(delta.isEmpty());
    }
}
