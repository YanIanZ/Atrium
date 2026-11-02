package dev.yanianz.atrium.module.moderation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.moderation.Punishment;
import dev.yanianz.atrium.api.moderation.PunishmentType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IpBanRulesTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static Punishment ipBan(String ip, Instant expires) {
        return new Punishment(1, PunishmentType.IPBAN, null, null, ip, null, "Console", "r", T0, expires,
                null, null, null, null);
    }

    @Test
    void temporaryIpBanMatchesUntilExpiry() {
        Punishment ban = ipBan("203.0.113.0/24", T0.plusSeconds(600));
        assertTrue(IpBanRules.matches(ban, "203.0.113.9", T0));
        assertTrue(IpBanRules.matches(ban, "203.0.113.9", T0.plusSeconds(599)));
        assertFalse(IpBanRules.matches(ban, "203.0.113.9", T0.plusSeconds(600)),
                "an expired temporary IP ban never matches again");
        assertFalse(IpBanRules.matches(ban, "203.0.114.9", T0), "outside the range");
    }

    @Test
    void ipv6AddressesAreComparedNormalised() {
        Punishment ban = ipBan("2001:db8:1:2::/64", null);
        assertTrue(IpBanRules.matches(ban, "2001:0db8:1:2:aaaa:bbbb:cccc:dddd", T0));
        assertFalse(IpBanRules.matches(ban, "2001:db8:1:3::1", T0));
        assertFalse(IpBanRules.matches(ban, "203.0.113.9", T0), "different address families never match");
    }

    @Test
    void revokedOrOtherPunishmentsNeverMatch() {
        Punishment ban = ipBan("203.0.113.9", null).revoked(null, "Console", T0);
        assertFalse(IpBanRules.matches(ban, "203.0.113.9", T0));
        Punishment mute = new Punishment(2, PunishmentType.MUTE, PLAYER, "Steve", null, null, "Console", "r", T0,
                null, null, null, null, null);
        assertFalse(IpBanRules.matches(mute, "203.0.113.9", T0));
    }
}
