package dev.yanianz.atrium.module.holograms;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LineContentTest {
    @Test void parsesItemLinesCaseInsensitively() {
        assertEquals(new LineContent.Item("diamond_sword"), LineContent.parse("#item:diamond_sword"));
        assertEquals(new LineContent.Item("minecraft:stone"), LineContent.parse("#ITEM: Minecraft:Stone "));
    }

    @Test void parsesSavedItemsBeforeTheShorterItemPrefix() {
        assertEquals(new LineContent.SavedItem("AAEC"), LineContent.parse("#itemstack:AAEC"));
    }

    @Test void parsesBlockDataKeepingItsCase() {
        assertEquals(new LineContent.Block("oak_stairs[facing=east]"), LineContent.parse("#block:oak_stairs[facing=east]"));
        assertEquals(new LineContent.Block("minecraft:stone"), LineContent.parse("#Block:minecraft:stone"));
    }

    @Test void everythingElseIsText() {
        assertEquals(new LineContent.Text("<gold>Hello %player_name%"), LineContent.parse("<gold>Hello %player_name%"));
        assertEquals(new LineContent.Text(""), LineContent.parse(""));
        assertEquals(new LineContent.Text("#items are cool"), LineContent.parse("#items are cool"));
        assertEquals(new LineContent.Text("#hand"), LineContent.parse("#hand"));
        assertTrue(LineContent.parse("plain").isText());
        assertFalse(LineContent.parse("#item:stone").isText());
    }

    @Test void backslashEscapesAPrefix() {
        assertEquals(new LineContent.Text("#item:stone"), LineContent.parse("\\#item:stone"));
    }

    @Test void prefixWithoutValueIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> LineContent.parse("#item:"));
        assertThrows(IllegalArgumentException.class, () -> LineContent.parse("#block:   "));
        assertThrows(IllegalArgumentException.class, () -> LineContent.parse("#itemstack:"));
    }
}
