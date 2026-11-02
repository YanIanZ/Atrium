package dev.yanianz.atrium.module.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CommandMatcherTest {
    @Test
    void wordsStripSlashAndNamespace() {
        assertEquals(List.of("msg", "bob", "hi"), CommandMatcher.words("/minecraft:MSG Bob  hi"));
        assertEquals(List.of("me"), CommandMatcher.words("//me"));
        assertEquals(List.of(), CommandMatcher.words("/"));
    }

    @Test
    void matchesWholeWordsOnly() {
        List<String> entries = List.of("msg", "mail send", "r");
        assertTrue(CommandMatcher.matches("/msg Bob hi", entries));
        assertTrue(CommandMatcher.matches("/essentials:msg Bob hi", entries));
        assertFalse(CommandMatcher.matches("/msgtoggle", entries), "prefix of a word is not a match");
        assertTrue(CommandMatcher.matches("/mail send Bob hi", entries));
        assertFalse(CommandMatcher.matches("/mail read", entries));
        assertFalse(CommandMatcher.matches("/rtp", entries), "short entries do not open other commands");
        assertTrue(CommandMatcher.matches("/R hi", entries));
        assertFalse(CommandMatcher.matches("/", entries));
    }

    @Test
    void blockedWithAllowList() {
        List<String> blocked = List.of("*");
        List<String> allowed = List.of("msg", "helpop");
        assertTrue(CommandMatcher.blocked("/spawn", blocked, allowed));
        assertFalse(CommandMatcher.blocked("/msg Staff help", blocked, allowed));
        assertTrue(CommandMatcher.blocked("/msgx", blocked, allowed));
        assertFalse(CommandMatcher.blocked("/spawn", List.of("home"), allowed));
    }
}
