package dev.yanianz.atrium.module.player;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NameChangeWatcherTest {
    @Test void replacesOnlySupportedPlaceholders() {
        assertEquals("mail send New Welcome back, Old",
                NameChangeWatcher.render("mail send {new} Welcome back, {old}", "Old", "New"));
        assertEquals("say Old New", NameChangeWatcher.render("say [oldname] [newname]", "Old", "New"));
        assertEquals("say {player}", NameChangeWatcher.render("say {player}", "Old", "New"));
        assertEquals("", NameChangeWatcher.render(null, "Old", "New"));
    }

    @Test void identityKeepsThePreviousNameAndChangeTime() {
        PlayerIdentity identity = PlayerIdentity.EMPTY;
        assertFalse(identity.hasName());
        PlayerIdentity named = identity.withName("Alice", 100L);
        assertTrue(named.hasName());
        assertEquals("Alice", named.lastName());
        assertEquals(100L, named.changedAt());
        assertEquals("Bob", named.withName("Bob", 200L).lastName());
        assertEquals(100L, named.changedAt(), "withName must not mutate the old value");
        assertFalse(new PlayerIdentity(null, 0L).hasName());
    }
}
