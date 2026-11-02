package dev.yanianz.atrium.core.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ShiftInsertTest {
    private static ShiftInsert.SlotState<String> slot(int index, String item, int amount) {
        return new ShiftInsert.SlotState<>(index, item, amount, 64);
    }

    @Test
    void topsUpSimilarStacksBeforeUsingEmptySlots() {
        var plan = ShiftInsert.plan(List.of(slot(0, null, 0), slot(1, "stone", 60), slot(2, "dirt", 10)),
                "stone", 10, 64, String::equals);
        assertEquals(List.of(new ShiftInsert.Put(1, 4, false), new ShiftInsert.Put(0, 6, true)), plan.puts());
        assertEquals(0, plan.remainder());
    }

    @Test
    void neverExceedsCapacityAndReportsRemainder() {
        var plan = ShiftInsert.plan(List.of(slot(0, "stone", 64), slot(1, "dirt", 1)), "stone", 5, 64, String::equals);
        assertTrue(plan.puts().isEmpty());
        assertEquals(5, plan.remainder());
        assertEquals(0, plan.moved(5));
    }

    @Test
    void respectsTheMovingItemsOwnMaxStack() {
        var plan = ShiftInsert.plan(List.of(slot(0, null, 0), slot(1, null, 0)), "pearl", 20, 16, String::equals);
        assertEquals(List.of(new ShiftInsert.Put(0, 16, true), new ShiftInsert.Put(1, 4, true)), plan.puts());
    }

    @Test
    void zeroOrNegativeAmountMovesNothing() {
        assertTrue(ShiftInsert.plan(List.of(slot(0, null, 0)), "x", 0, 64, String::equals).puts().isEmpty());
        assertTrue(ShiftInsert.plan(List.of(slot(0, null, 0)), "x", -3, 64, String::equals).puts().isEmpty());
    }
}
