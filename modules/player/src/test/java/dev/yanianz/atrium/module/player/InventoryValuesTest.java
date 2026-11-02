package dev.yanianz.atrium.module.player;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryValuesTest {
    @Test void amountsRejectZeroNegativeOverflowAndFractions() {
        assertEquals(Integer.MAX_VALUE, InventoryValues.amount("all"));
        assertEquals(64, InventoryValues.amount("64"));
        for (String invalid : List.of("0", "-1", "2147483648", "1.5", "+2", ""))
            assertThrows(IllegalArgumentException.class, () -> InventoryValues.amount(invalid));
    }
    @Test void loreUsesOneBasedLinesAndDoesNotMutateInput() {
        var a = Component.text("a"); var b = Component.text("b");
        var original = List.of(a, b);
        assertEquals(List.of(b, b), InventoryValues.lore(original, "set", "1", b));
        assertEquals(List.of(a, a, b), InventoryValues.lore(original, "insert", "1", a));
        assertEquals(List.of(a, b, a), InventoryValues.lore(original, "insert", "3", a));
        assertEquals(List.of(a), InventoryValues.lore(original, "remove", "2", null));
        assertEquals(List.of(), InventoryValues.lore(original, "remove", "*", null));
        assertEquals(List.of(a, a), InventoryValues.lore(original, "set", "*", a));
        assertEquals(List.of(a, b, a), InventoryValues.lore(original, "add", "", a));
        assertEquals(List.of(), InventoryValues.lore(original, "clear", "", null));
        assertEquals(List.of(a, b), original);
        assertThrows(IllegalArgumentException.class, () -> InventoryValues.lore(original, "set", "0", a));
        assertThrows(IllegalArgumentException.class, () -> InventoryValues.lore(original, "remove", "3", null));
        assertThrows(IllegalArgumentException.class, () -> InventoryValues.lore(original, "insert", "4", a));
        assertThrows(IllegalArgumentException.class, () -> InventoryValues.lore(original, "set", "one", a));
    }
    @Test void unprivilegedTextIsEntirelyLiteral() {
        String text = "<red>Hello</red><click:run_command:'/op me'>x</click>%player_name%";
        assertEquals(Component.text(text), InventoryValues.text(text, false));
    }
    @Test void privilegedTextOnlyEnablesColorAndDecoration() {
        assertEquals(Component.text("Hello", NamedTextColor.RED), InventoryValues.text("<red>Hello</red>", true).compact());
        for (String text : List.of("<click:run_command:'/op me'>x</click>", "<hover:show_text:'hi'>x</hover>",
                "<insertion:secret>x</insertion>", "<selector:@a>", "<font:minecraft:alt>x</font>")) {
            assertEquals(Component.text(text), InventoryValues.text(text, true));
        }
    }
}
