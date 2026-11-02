package dev.yanianz.atrium.core.menu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.inventory.InventoryAction;
import org.junit.jupiter.api.Test;

class MenuMathTest {
    @Test
    void paginatorMath() {
        Paginator p = new Paginator(45, 20);
        assertEquals(3, p.pageCount());
        assertEquals(0, p.fromIndex(0));
        assertEquals(20, p.toIndex(0));
        assertEquals(40, p.fromIndex(2));
        assertEquals(45, p.toIndex(2));
        assertEquals(2, p.clamp(99));
        assertEquals(0, p.clamp(-3));
        assertFalse(p.hasPrevious(0));
        assertTrue(p.hasNext(1));
        assertFalse(p.hasNext(2));
        List<Integer> items = IntStream.range(0, 45).boxed().toList();
        assertEquals(List.of(40, 41, 42, 43, 44), p.slice(items, 7));
    }

    @Test
    void emptyListHasOnePage() {
        Paginator p = new Paginator(0, 10);
        assertEquals(1, p.pageCount());
        assertEquals(0, p.fromIndex(5));
        assertEquals(0, p.toIndex(5));
        assertFalse(p.hasNext(0));
        assertThrows(IllegalArgumentException.class, () -> new Paginator(3, 0));
    }

    @Test
    void slotLayout() {
        assertEquals(13, SlotLayout.slot(1, 4));
        assertEquals(1, SlotLayout.row(13));
        assertEquals(4, SlotLayout.column(13));
        assertEquals(45, SlotLayout.contentAboveNavigation(6).length);
        assertEquals(0, SlotLayout.contentAboveNavigation(1).length);
        assertEquals(53, SlotLayout.bottom(6, SlotLayout.CLOSE_COLUMN));
        assertEquals(22, SlotLayout.bottom(3, SlotLayout.PAGE_COLUMN));
        int[] framed = SlotLayout.framedContent(6);
        assertEquals(28, framed.length);
        assertEquals(10, framed[0]);
        assertEquals(43, framed[framed.length - 1]);
        assertArrayEquals(new int[] {10, 11, 12, 13, 14, 15, 16}, SlotLayout.framedContent(3));
        assertThrows(IllegalArgumentException.class, () -> SlotLayout.checkRows(7));
        assertThrows(IllegalArgumentException.class, () -> SlotLayout.slot(0, 9));
    }

    @Test
    void debounceRejectsFastClicksPerPlayer() {
        AtomicLong now = new AtomicLong(1_000);
        ClickDebounce debounce = new ClickDebounce(now::get);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertTrue(debounce.tryClick(a, 150));
        assertTrue(debounce.tryClick(b, 150), "other players are independent");
        now.addAndGet(100);
        assertFalse(debounce.tryClick(a, 150));
        now.addAndGet(60); // 160 ms after the accepted click; the rejected one did not extend the window
        assertTrue(debounce.tryClick(a, 150));
        assertTrue(debounce.tryClick(a, 0), "window 0 disables debounce");
        debounce.forget(a);
        debounce.forget(b);
        assertEquals(0, debounce.tracked());
    }

    @Test
    void clickPolicyDeniesEverythingButInputSlots() {
        Set<Integer> none = Set.of();
        Set<Integer> inputs = Set.of(13);
        // No inputs: nothing at all, in the menu or the player inventory.
        for (InventoryAction action : InventoryAction.values()) {
            assertFalse(ClickPolicy.allowsClick(13, 27, action, none, true), action.name());
            assertFalse(ClickPolicy.allowsClick(40, 27, action, none, true), action.name());
        }
        assertTrue(ClickPolicy.allowsClick(13, 27, InventoryAction.PLACE_ALL, inputs, false));
        assertTrue(ClickPolicy.allowsClick(13, 27, InventoryAction.MOVE_TO_OTHER_INVENTORY, inputs, false));
        assertTrue(ClickPolicy.allowsClick(13, 27, InventoryAction.HOTBAR_SWAP, inputs, false));
        assertFalse(ClickPolicy.allowsClick(12, 27, InventoryAction.PICKUP_ALL, inputs, true), "non-input menu slot");
        assertFalse(ClickPolicy.allowsClick(13, 27, InventoryAction.COLLECT_TO_CURSOR, inputs, true), "double click");
        assertFalse(ClickPolicy.allowsClick(-999, 27, InventoryAction.DROP_ALL_CURSOR, inputs, true), "outside");
        // Player inventory: only plain moves and only when enabled; never shift-click into the menu.
        assertFalse(ClickPolicy.allowsClick(40, 27, InventoryAction.PICKUP_ALL, inputs, false));
        assertTrue(ClickPolicy.allowsClick(40, 27, InventoryAction.PICKUP_ALL, inputs, true));
        assertFalse(ClickPolicy.allowsClick(40, 27, InventoryAction.MOVE_TO_OTHER_INVENTORY, inputs, true));
        assertFalse(ClickPolicy.allowsClick(40, 27, InventoryAction.COLLECT_TO_CURSOR, inputs, true));
    }

    @Test
    void dragPolicy() {
        Set<Integer> inputs = Set.of(10, 11);
        assertTrue(ClickPolicy.allowsDrag(Set.of(10, 11), 27, inputs, false));
        assertFalse(ClickPolicy.allowsDrag(Set.of(10, 12), 27, inputs, true));
        assertFalse(ClickPolicy.allowsDrag(Set.of(10, 30), 27, inputs, false));
        assertTrue(ClickPolicy.allowsDrag(Set.of(10, 30), 27, inputs, true));
        assertFalse(ClickPolicy.allowsDrag(Set.of(30), 27, Set.of(), true), "no inputs: drags never allowed");
    }

    @Test
    void loreLinesSplitAtNewlinesKeepingStyle() {
        Component c = Component.text("one\ntwo", NamedTextColor.RED).append(Component.text("\nthree"));
        List<Component> lines = Lines.split(c);
        assertEquals(3, lines.size());
        var plain = PlainTextComponentSerializer.plainText();
        assertEquals("one", plain.serialize(lines.get(0)));
        assertEquals("two", plain.serialize(lines.get(1)));
        assertEquals("three", plain.serialize(lines.get(2)));
        assertEquals(NamedTextColor.RED, lines.get(2).children().getFirst().color(), "inherited colour kept");
        assertEquals(List.of(Component.text("x")), Lines.split(Component.text("x")));
    }
}
