package dev.yanianz.atrium.module.inspect;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UserDataAdminGroupTest {
    @Test
    void groupsMatchOnlyTheirOwnTables() {
        assertTrue(UserDataAdmin.Group.HOMES.matches("homes_homes"));
        assertFalse(UserDataAdmin.Group.HOMES.matches("kits_usage"));
        assertTrue(UserDataAdmin.Group.PLAYTIME.matches("playtime_daily"));
        assertTrue(UserDataAdmin.Group.COOLDOWNS.matches("cooldowns"));
        assertTrue(UserDataAdmin.Group.TELEPORT.matches("teleport_players"));
        assertTrue(UserDataAdmin.Group.TELEPORT.matches("rtp_cooldowns"));
        assertTrue(UserDataAdmin.Group.PLAYER.matches("playerplus_state"));
        assertTrue(UserDataAdmin.Group.PLAYER.matches("player_state"));
        assertTrue(UserDataAdmin.Group.ECONOMY.matches("economy_transactions"));
        assertTrue(UserDataAdmin.Group.SEEN.matches("seen_logout"));
        assertTrue(UserDataAdmin.Group.ALL.matches("anything_at_all"));
    }

    @Test
    void parseIsCaseInsensitiveAndRejectsUnknownGroups() {
        assertSame(UserDataAdmin.Group.KITS, UserDataAdmin.Group.parse(" KiTs "));
        assertSame(UserDataAdmin.Group.ALL, UserDataAdmin.Group.parse("all"));
        assertNull(UserDataAdmin.Group.parse("everything"));
        assertNull(UserDataAdmin.Group.parse(null));
    }
}
