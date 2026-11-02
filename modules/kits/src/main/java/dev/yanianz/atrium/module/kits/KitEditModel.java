package dev.yanianz.atrium.module.kits;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure item accounting for the GUI kit editor. It only tracks where each slot came from, never item data:
 * <ul>
 *   <li>{@link Origin#KIT} - a copy of an item that was already part of the kit;</li>
 *   <li>{@link Origin#ADMIN} - an item taken out of the admin's own inventory.</li>
 * </ul>
 * Closing without saving returns exactly the {@code ADMIN} slots (and the icon) to the admin. Saving consumes
 * them into the kit. A {@code KIT} copy is never returned, which is what makes duplication impossible.
 */
final class KitEditModel {
    enum Origin { EMPTY, KIT, ADMIN }

    private final Origin[] slots;
    private boolean iconBorrowed;

    KitEditModel(int size) {
        if (size <= 0) throw new IllegalArgumentException("size must be positive");
        this.slots = new Origin[size];
        java.util.Arrays.fill(slots, Origin.EMPTY);
    }

    /** Marks the slots that start with kit copies. */
    static KitEditModel forKit(int size, java.util.Collection<Integer> kitSlots) {
        KitEditModel model = new KitEditModel(size);
        for (int slot : kitSlots) model.markKit(slot);
        return model;
    }

    int size() {
        return slots.length;
    }

    Origin origin(int slot) {
        check(slot);
        return slots[slot];
    }

    boolean isEmpty(int slot) {
        return origin(slot) == Origin.EMPTY;
    }

    boolean isBorrowed(int slot) {
        return origin(slot) == Origin.ADMIN;
    }

    void markKit(int slot) {
        check(slot);
        slots[slot] = Origin.KIT;
    }

    /** Places an item in a slot and returns the previous origin, so the caller can return replaced items. */
    Origin place(int slot, boolean fromAdmin) {
        check(slot);
        Origin previous = slots[slot];
        slots[slot] = fromAdmin ? Origin.ADMIN : Origin.KIT;
        return previous;
    }

    /** Removes a slot and returns what used to be there. */
    Origin remove(int slot) {
        check(slot);
        Origin previous = slots[slot];
        slots[slot] = Origin.EMPTY;
        return previous;
    }

    void setIcon(boolean fromAdmin) {
        iconBorrowed = fromAdmin;
    }

    void clearIcon() {
        iconBorrowed = false;
    }

    boolean iconBorrowed() {
        return iconBorrowed;
    }

    /** Slots the admin must get back when the editor is closed without saving. */
    List<Integer> cancelReturns() {
        List<Integer> slotsToReturn = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == Origin.ADMIN) slotsToReturn.add(i);
        }
        return List.copyOf(slotsToReturn);
    }

    boolean hasBorrowedItems() {
        if (iconBorrowed) return true;
        for (Origin origin : slots) if (origin == Origin.ADMIN) return true;
        return false;
    }

    /**
     * Slots the admin may put items into and take items out of (menu input slots): every slot except kit
     * copies. Kit copies stay locked buttons, so they can never leave the editor.
     */
    List<Integer> inputSlots() {
        List<Integer> inputs = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != Origin.KIT) inputs.add(i);
        }
        return List.copyOf(inputs);
    }

    /**
     * Records what the admin left in an input slot when the items are taken back out of the menu: an item there
     * is borrowed, an empty slot holds nothing. A kit copy is never an input slot, so it is refused here.
     */
    void harvested(int slot, boolean present) {
        check(slot);
        if (slots[slot] == Origin.KIT) throw new IllegalStateException("slot " + slot + " holds a kit copy");
        slots[slot] = present ? Origin.ADMIN : Origin.EMPTY;
    }

    /** Saving turns every borrowed item into kit content; nothing is returned afterwards. */
    void commit() {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == Origin.ADMIN) slots[i] = Origin.KIT;
        }
        iconBorrowed = false;
    }

    /** A copy of the tracked origins, for tests and diagnostics. */
    List<Origin> origins() {
        return List.of(slots);
    }

    private void check(int slot) {
        if (slot < 0 || slot >= slots.length) throw new IndexOutOfBoundsException("slot " + slot);
    }
}
