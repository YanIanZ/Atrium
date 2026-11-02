package dev.yanianz.atrium.module.kits;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KitEditModelTest {
    @Test
    void kitCopiesAreNeverReturned() {
        KitEditModel model = KitEditModel.forKit(41, Set.of(0, 5, 40));
        assertEquals(KitEditModel.Origin.KIT, model.origin(0));
        assertEquals(KitEditModel.Origin.EMPTY, model.origin(1));
        assertTrue(model.cancelReturns().isEmpty());
        assertFalse(model.hasBorrowedItems());
    }

    @Test
    void adminItemsAreReturnedOnCancel() {
        KitEditModel model = KitEditModel.forKit(41, Set.of(0));
        assertEquals(KitEditModel.Origin.EMPTY, model.place(10, true));
        assertEquals(KitEditModel.Origin.EMPTY, model.place(11, true));
        assertTrue(model.hasBorrowedItems());
        assertEquals(List.of(10, 11), model.cancelReturns());
    }

    @Test
    void replacingAkitSlotWithAnAdminItemTracksTheReplacement() {
        KitEditModel model = KitEditModel.forKit(41, Set.of(4));
        assertEquals(KitEditModel.Origin.KIT, model.place(4, true));
        assertEquals(List.of(4), model.cancelReturns());
        assertEquals(KitEditModel.Origin.ADMIN, model.origin(4));
    }

    @Test
    void removingABorrowedItemTakesItOutOfTheCancelSet() {
        KitEditModel model = KitEditModel.forKit(41, Set.of());
        model.place(7, true);
        assertEquals(KitEditModel.Origin.ADMIN, model.remove(7));
        assertTrue(model.cancelReturns().isEmpty());
    }

    @Test
    void savingConsumesBorrowedItems() {
        KitEditModel model = KitEditModel.forKit(41, Set.of(1));
        model.place(2, true);
        model.place(3, true);
        model.setIcon(true);
        model.commit();
        assertEquals(List.of(), model.cancelReturns());
        assertFalse(model.iconBorrowed());
        assertFalse(model.hasBorrowedItems());
        assertEquals(KitEditModel.Origin.KIT, model.origin(2));
    }

    @Test
    void placingTwiceAtTheSameSlotOnlyReturnsOneItem() {
        KitEditModel model = KitEditModel.forKit(41, Set.of());
        model.place(9, true);
        assertEquals(KitEditModel.Origin.ADMIN, model.place(9, true));
        assertEquals(List.of(9), model.cancelReturns());
    }

    @Test
    void iconIsTrackedSeparately() {
        KitEditModel model = KitEditModel.forKit(41, Set.of());
        model.setIcon(true);
        assertTrue(model.iconBorrowed());
        assertTrue(model.hasBorrowedItems());
        model.clearIcon();
        assertFalse(model.iconBorrowed());
        assertFalse(model.hasBorrowedItems());
    }

    @Test
    void rejectsInvalidSlots() {
        KitEditModel model = new KitEditModel(3);
        assertThrows(IndexOutOfBoundsException.class, () -> model.origin(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> model.place(3, true));
        assertThrows(IllegalArgumentException.class, () -> new KitEditModel(0));
    }

    @Test
    void kitCopiesAreNeverInputSlots() {
        KitEditModel model = KitEditModel.forKit(5, Set.of(1, 3));
        model.place(4, true);
        assertEquals(List.of(0, 2, 4), model.inputSlots());
        assertThrows(IllegalStateException.class, () -> model.harvested(1, true));
    }

    @Test
    void harvestingTracksWhatTheAdminLeftInTheMenu() {
        KitEditModel model = KitEditModel.forKit(4, Set.of(0));
        model.place(1, true);
        model.harvested(1, false); // the admin took the item back out
        model.harvested(2, true);  // the admin put an item in
        assertEquals(List.of(2), model.cancelReturns());
        model.commit();
        assertEquals(List.of(1, 3), model.inputSlots());
    }

    @Test
    void replacedKitSlotBecomesAnInputSlot() {
        KitEditModel model = KitEditModel.forKit(3, Set.of(0, 1));
        assertEquals(List.of(2), model.inputSlots());
        model.place(0, true);
        model.remove(1);
        assertEquals(List.of(0, 1, 2), model.inputSlots());
    }
}
