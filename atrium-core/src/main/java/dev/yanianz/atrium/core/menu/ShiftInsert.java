package dev.yanianz.atrium.core.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * Plans a shift-click from the player's inventory into a menu: items may only land in the menu's declared
 * input slots, never in display slots (vanilla would merge into any similar stack, including fillers).
 * First tops up similar stacks in input-slot order, then fills empty input slots. Pure, so it is unit-tested;
 * the caller applies the plan on the viewer's thread after re-validating the source slot.
 */
public final class ShiftInsert {
    /** Amount to add to one input slot ({@code empty} = the slot was empty and receives a new stack). */
    public record Put(int slot, int amount, boolean empty) {}

    /** All puts and what is left of the moved stack. */
    public record Plan(List<Put> puts, int remainder) {
        public int moved(int original) {
            return original - remainder;
        }
    }

    /** View of one slot's current content. {@code amount == 0} means empty. */
    public record SlotState<T>(int slot, T item, int amount, int maxStack) {}

    private ShiftInsert() {}

    /**
     * @param slots input slots in order, with their current content
     * @param moving the stack being shift-clicked
     * @param amount its amount
     * @param maxStack its max stack size
     * @param similar whether a slot item can merge with the moving item
     */
    public static <T> Plan plan(List<SlotState<T>> slots, T moving, int amount, int maxStack, BiPredicate<T, T> similar) {
        int left = Math.max(0, amount);
        List<Put> puts = new ArrayList<>();
        for (SlotState<T> slot : slots) {
            if (left == 0) {
                break;
            }
            if (slot.amount() > 0 && similar.test(slot.item(), moving)) {
                int room = Math.max(0, Math.min(slot.maxStack(), maxStack) - slot.amount());
                int add = Math.min(room, left);
                if (add > 0) {
                    puts.add(new Put(slot.slot(), add, false));
                    left -= add;
                }
            }
        }
        for (SlotState<T> slot : slots) {
            if (left == 0) {
                break;
            }
            if (slot.amount() == 0) {
                int add = Math.min(maxStack, left);
                puts.add(new Put(slot.slot(), add, true));
                left -= add;
            }
        }
        return new Plan(List.copyOf(puts), left);
    }
}
