package dev.yanianz.atrium.module.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DiscordJsonTest {
    @Test
    void quotesSpecialCharacters() {
        assertEquals("\"a\\\"b\\\\c\\nd\\re\\tf\\bg\\fh\"",
                DiscordJson.quote("a\"b\\c\nd\re\tf\bg\fh"));
    }

    @Test
    void escapesControlAndLineSeparatorCharacters() {
        assertEquals("\"\\u0001\\u001f\\u2028\\u2029\"", DiscordJson.quote("\u0001\u001f\u2028\u2029"));
    }

    @Test
    void keepsUnicode() {
        assertEquals("\"héllo 😀\"", DiscordJson.quote("héllo 😀"));
    }

    @Test
    void payloadAlwaysDisablesMentions() {
        String json = DiscordJson.payload("hi", "bob", "https://example.com/a.png");
        assertEquals("{\"content\":\"hi\",\"username\":\"bob\",\"avatar_url\":\"https://example.com/a.png\","
                + "\"allowed_mentions\":{\"parse\":[]}}", json);
    }

    @Test
    void payloadOmitsBlankUsernameAndUnsafeAvatar() {
        String json = DiscordJson.payload("hi", "  ", "http://example.com/a.png");
        assertEquals("{\"content\":\"hi\",\"allowed_mentions\":{\"parse\":[]}}", json);
        assertFalse(json.contains("username"));
        assertFalse(json.contains("avatar_url"));
    }

    @Test
    void payloadOmitsMissingAvatar() {
        String json = DiscordJson.payload("hi", "bob", null);
        assertTrue(json.contains("\"username\":\"bob\""));
        assertFalse(json.contains("avatar_url"));
    }
}
