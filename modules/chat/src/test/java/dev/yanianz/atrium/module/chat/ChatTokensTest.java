package dev.yanianz.atrium.module.chat;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatTokensTest {
    @Test
    void parsesItemTokens() {
        ChatTokens.Parsed parsed = ChatTokens.parse("look [item] and [item:hand] and [item:12]", 10);

        assertEquals(3, parsed.tokens().size());
        assertTrue(parsed.tokens().get(0) instanceof ChatTokens.Item);
        assertEquals("", ((ChatTokens.Item) parsed.tokens().get(0)).slot());
        assertEquals("hand", ((ChatTokens.Item) parsed.tokens().get(1)).slot());
        assertEquals("12", ((ChatTokens.Item) parsed.tokens().get(2)).slot());
        assertFalse(parsed.text().contains("[item"));
        assertTrue(parsed.text().contains(parsed.tokens().get(0).sentinel()));
    }

    @Test
    void parsesSpritesWithAndWithoutAtlas() {
        ChatTokens.Parsed parsed = ChatTokens.parse("[sprite:heart] [sprite:minecraft:food]", 10);

        ChatTokens.Sprite first = (ChatTokens.Sprite) parsed.tokens().get(0);
        ChatTokens.Sprite second = (ChatTokens.Sprite) parsed.tokens().get(1);
        assertEquals("", first.atlas());
        assertEquals("heart", first.name());
        assertEquals("minecraft", second.atlas());
        assertEquals("food", second.name());
    }

    @Test
    void limitKeepsTheRestAsLiteralText() {
        ChatTokens.Parsed parsed = ChatTokens.parse("[item] [item] [item]", 1);

        assertEquals(1, parsed.tokens().size());
        assertEquals(2, parsed.text().split("\\[item]", -1).length - 1);
    }

    @Test
    void typedSentinelsAreRemovedAndUnknownBracketsStayLiteral() {
        ChatTokens.Parsed parsed = ChatTokens.parse("a\uE0000\uE001 b [nope]", 5);

        assertEquals("a0 b [nope]", parsed.text());
        assertTrue(parsed.tokens().isEmpty());
    }

    @Test
    void zeroLimitDisablesParsing() {
        ChatTokens.Parsed parsed = ChatTokens.parse("[item]", 0);
        assertEquals("[item]", parsed.text());
        assertTrue(parsed.tokens().isEmpty());
    }

    @Test
    void slotNamesMapToInventoryIndexes() {
        assertEquals(-1, ChatItems.slotIndex("hand").orElseThrow());
        assertEquals(40, ChatItems.slotIndex("offhand").orElseThrow());
        assertEquals(39, ChatItems.slotIndex("head").orElseThrow());
        assertEquals(38, ChatItems.slotIndex("chest").orElseThrow());
        assertEquals(37, ChatItems.slotIndex("legs").orElseThrow());
        assertEquals(36, ChatItems.slotIndex("feet").orElseThrow());
        assertEquals(5, ChatItems.slotIndex("5").orElseThrow());
        assertTrue(ChatItems.slotIndex("nope").isEmpty());
        assertTrue(ChatItems.slotIndex("99").isEmpty());
    }
}
