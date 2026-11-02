package dev.yanianz.atrium.module.worldrules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.worldrules.ArmorChangeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

/** Armour slot mapping of the public {@link ArmorChangeEvent}. */
class ArmorChangeSlotTest {
    @Test
    void onlyTheFourArmourSlotsAreAccepted() {
        assertTrue(ArmorChangeEvent.isArmorSlot(EquipmentSlot.HEAD));
        assertTrue(ArmorChangeEvent.isArmorSlot(EquipmentSlot.CHEST));
        assertTrue(ArmorChangeEvent.isArmorSlot(EquipmentSlot.LEGS));
        assertTrue(ArmorChangeEvent.isArmorSlot(EquipmentSlot.FEET));
    }

    @Test
    void handsAndBodyAreNotArmourSlots() {
        assertFalse(ArmorChangeEvent.isArmorSlot(EquipmentSlot.HAND));
        assertFalse(ArmorChangeEvent.isArmorSlot(EquipmentSlot.OFF_HAND));
        assertFalse(ArmorChangeEvent.isArmorSlot(EquipmentSlot.BODY));
    }
}
