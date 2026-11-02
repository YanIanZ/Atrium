package dev.yanianz.atrium.module.chat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmojiReplacerTest {
    private static EmojiReplacer.Table table() {
        return EmojiReplacer.compile(codes(
                "heart", "\u2764",
                "fire", "\uD83D\uDD25",
                "a", "A",
                "b", "B",
                "smile", "\uD83D\uDE04"), message -> fail(message));
    }

    @Test
    void replacesWholeShortcodesOnly() {
        assertEquals("\u2764 love", EmojiReplacer.replace(":heart: love", table()));
        assertEquals("not :heartbeat: here", EmojiReplacer.replace("not :heartbeat: here", table()));
        assertEquals("no shortcode", EmojiReplacer.replace("no shortcode", table()));
    }

    @Test
    void shortcodesAreMatchedAsWholeTokensAndNeverPartially() {
        assertEquals("A", EmojiReplacer.replace(":a:", table()));
        // ':a:extra' is not a token: the character after the closing colon is a word character.
        assertEquals(":a:extra", EmojiReplacer.replace(":a:extra", table()));
        assertEquals("A B", EmojiReplacer.replace(":a: :b:", table()));
        assertEquals("not :smileys: here", EmojiReplacer.replace("not :smileys: here", table()));
    }

    @Test
    void adjacentShortcodesBothReplace() {
        assertEquals("\u2764\uD83D\uDD25", EmojiReplacer.replace(":heart::fire:", table()));
    }

    @Test
    void urlsAreNeverTouched() {
        assertEquals("https://example.com/:heart: and \u2764",
                EmojiReplacer.replace("https://example.com/:heart: and :heart:", table()));
        assertEquals("www.example.com/:fire:", EmojiReplacer.replace("www.example.com/:fire:", table()));
        assertEquals("ftp://host/:heart:!", EmojiReplacer.replace("ftp://host/:heart:!", table()));
    }

    @Test
    void replacementTextIsNeverRescanned() {
        EmojiReplacer.Table loop = EmojiReplacer.compile(codes("loop", ":loop:"), message -> fail(message));
        assertEquals(":loop:", EmojiReplacer.replace(":loop:", loop));
    }

    @Test
    void invalidEntriesAreSkipped() {
        Map<String, String> codes = new LinkedHashMap<>();
        codes.put("bad name", "x");
        codes.put("ok", "");
        codes.put("", "x");
        codes.put("good", "G");
        List<String> warnings = new java.util.ArrayList<>();
        EmojiReplacer.Table table = EmojiReplacer.compile(codes, warnings::add);
        assertEquals("G", EmojiReplacer.replace(":good:", table));
        assertEquals(3, warnings.size());
    }

    @Test
    void textWithoutCodesIsReturnedUnchanged() {
        assertEquals("", EmojiReplacer.replace(null, table()));
        assertEquals("plain", EmojiReplacer.replace("plain", EmojiReplacer.Table.EMPTY));
    }

    private static Map<String, String> codes(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
