package dev.yanianz.atrium.module.chat;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommandSpamGuardTest {
    private static final UUID PLAYER = UUID.randomUUID();

    @Test
    void blocksTheSameCommandAfterTheLimit() {
        CommandSpamGuard guard = new CommandSpamGuard();
        CommandSpamGuard.Settings settings = new CommandSpamGuard.Settings(true, 3, 5000, Set.of());

        assertEquals(CommandSpamGuard.Result.ALLOWED, guard.check(PLAYER, "/spawn", 0, settings));
        assertEquals(CommandSpamGuard.Result.ALLOWED, guard.check(PLAYER, "/spawn", 100, settings));
        assertEquals(CommandSpamGuard.Result.ALLOWED, guard.check(PLAYER, "/spawn", 200, settings));
        assertEquals(CommandSpamGuard.Result.TOO_MANY, guard.check(PLAYER, "/spawn", 300, settings));
        assertEquals(CommandSpamGuard.Result.ALLOWED, guard.check(PLAYER, "/spawn", 5000, settings));
    }

    @Test
    void whitelistedCommandsAndOtherPlayersAreIndependent() {
        CommandSpamGuard guard = new CommandSpamGuard();
        CommandSpamGuard.Settings settings = new CommandSpamGuard.Settings(true, 1, 5000, Set.of("msg"));

        guard.check(PLAYER, "/msg someone", 0, settings);
        assertEquals(CommandSpamGuard.Result.ALLOWED, guard.check(PLAYER, "/msg again", 1, settings));
        assertEquals(CommandSpamGuard.Result.ALLOWED,
                guard.check(UUID.randomUUID(), "/spawn", 2, settings));
    }

    @Test
    void parsesNamespacedLabels() {
        assertEquals("msg", CommandSpamGuard.label("/atrium:msg hello"));
        assertEquals("spawn", CommandSpamGuard.label("  /spawn"));
        assertEquals("", CommandSpamGuard.label("/"));
    }

    @Test
    void disabledSettingsPass() {
        CommandSpamGuard guard = new CommandSpamGuard();
        CommandSpamGuard.Settings off = new CommandSpamGuard.Settings(false, 1, 5000, Set.of());
        assertTrue(guard.check(PLAYER, "/spawn", 0, off) == CommandSpamGuard.Result.ALLOWED);
        assertTrue(guard.check(PLAYER, "/spawn", 1, off) == CommandSpamGuard.Result.ALLOWED);
    }
}
