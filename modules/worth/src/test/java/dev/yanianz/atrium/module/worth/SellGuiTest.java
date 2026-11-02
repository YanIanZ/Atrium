package dev.yanianz.atrium.module.worth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SellGuiTest {
    @Test
    void rowsAreClampedToAChestMenu() {
        assertEquals(1, SellGui.rows(0));
        assertEquals(1, SellGui.rows(-5));
        assertEquals(3, SellGui.rows(3));
        assertEquals(6, SellGui.rows(9));
    }
}
