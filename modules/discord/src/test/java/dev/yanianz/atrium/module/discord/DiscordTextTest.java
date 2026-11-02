package dev.yanianz.atrium.module.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DiscordTextTest {
    @Test
    void escapesMarkdownCharacters() {
        assertEquals("\\*bold\\* \\_it\\_ \\~x\\~ \\`c\\` \\>q \\|p \\[a\\] \\\\s",
                DiscordText.sanitize("*bold* _it_ ~x~ `c` >q |p [a] \\s"));
    }

    @Test
    void collapsesLineBreaksAndControls() {
        assertEquals("a b c", DiscordText.sanitize("a\r\n\nb\tc"));
        assertEquals("a b", DiscordText.sanitize("a\u0001b"));
    }

    @Test
    void neutralisesBareLinks() {
        String escaped = DiscordText.sanitize("see https://evil.example/path");
        assertFalse(escaped.contains("://"));
        assertTrue(escaped.contains("\u200B"));
        assertTrue(escaped.startsWith("see https:"));
    }

    @Test
    void substituteValuesAreNeverRescanned() {
        String result = DiscordText.render("{message} {actor}", Map.of("message", "{actor}", "actor", "Admin"), true);
        assertEquals("{actor} Admin", result);
    }

    @Test
    void escapesValuesButNotTheTemplate() {
        assertEquals("**a\\*b**", DiscordText.render("**{player}**", Map.of("player", "a*b"), true));
        assertEquals("a*b", DiscordText.render("{player}", Map.of("player", "a*b"), false));
    }

    @Test
    void unknownPlaceholdersStayVisible() {
        assertEquals("{missing} x", DiscordText.render("{missing} {known}", Map.of("known", "x"), true));
    }

    @Test
    void truncatesToTheContentLimitWithAnEllipsis() {
        String longText = "a".repeat(2500);
        String limited = DiscordText.limitContent(longText);
        assertEquals(DiscordText.CONTENT_LIMIT, limited.length());
        assertTrue(limited.endsWith("…"));
    }

    @Test
    void neverSplitsASurrogatePair() {
        assertEquals("abc…", DiscordText.truncate("abc😀d", 5));
        assertEquals("…", DiscordText.truncate("😀😀", 2));
        assertEquals("😀", DiscordText.truncate("😀", 2));
    }

    @Test
    void usernameIsCleanedAndLimited() {
        assertEquals("a b", DiscordText.limitUsername("a\nb"));
        assertEquals(DiscordText.USERNAME_LIMIT, DiscordText.limitUsername("x".repeat(100)).length());
    }

    @Test
    void plainKeepsMarkdownButRemovesControls() {
        assertEquals("*x* y", DiscordText.plain(" *x*\u0000y "));
    }
}
