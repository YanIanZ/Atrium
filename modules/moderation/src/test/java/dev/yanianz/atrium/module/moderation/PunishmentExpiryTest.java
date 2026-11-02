package dev.yanianz.atrium.module.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.moderation.MuteInfo;
import dev.yanianz.atrium.api.moderation.Punishment;
import dev.yanianz.atrium.api.moderation.PunishmentRequest;
import dev.yanianz.atrium.api.moderation.PunishmentType;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PunishmentExpiryTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static Punishment of(long id, PunishmentType type, Instant expires) {
        return new Punishment(id, type, PLAYER, "Steve", null, null, "Console", "r", T0, expires, null, null, null, null);
    }

    @Test
    void expiryIsTimeComparison() {
        Punishment p = of(1, PunishmentType.TEMPBAN, T0.plusSeconds(60));
        assertTrue(p.isActive(T0));
        assertTrue(p.isActive(T0.plusSeconds(59)));
        assertFalse(p.isActive(T0.plusSeconds(60)), "expires exactly at the expiry instant");
        assertEquals(Optional.of(Duration.ofSeconds(30)), p.remaining(T0.plusSeconds(30)));
        assertEquals(Optional.of(Duration.ZERO), p.remaining(T0.plusSeconds(90)), "never negative");
    }

    @Test
    void permanentAndRevoked() {
        Punishment permanent = of(1, PunishmentType.BAN, null);
        assertTrue(permanent.isActive(T0.plus(Duration.ofDays(10_000))));
        assertTrue(permanent.remaining(T0).isEmpty());
        Punishment revoked = permanent.revoked(null, "Console", T0.plusSeconds(5));
        assertFalse(revoked.isActive(T0));
        assertTrue(revoked.isRevoked());
    }

    @Test
    void kicksAreNeverActive() {
        assertFalse(of(1, PunishmentType.KICK, null).isActive(T0));
    }

    @Test
    void groupsReplaceEachOther() {
        assertEquals(PunishmentType.BANS, PunishmentType.TEMPBAN.group());
        assertTrue(PunishmentType.SHADOWMUTE.group().contains(PunishmentType.MUTE));
        assertEquals(EnumSet.of(PunishmentType.WARN), PunishmentType.WARN.group());
    }

    @Test
    void cacheUsesTimeAndReplacesGroup() {
        ActiveCache cache = new ActiveCache();
        cache.put(PLAYER, List.of(of(1, PunishmentType.TEMPMUTE, T0.plusSeconds(60)), of(2, PunishmentType.WARN, null)));
        assertTrue(cache.active(PLAYER, PunishmentType.MUTES, T0).isPresent());
        assertTrue(cache.active(PLAYER, PunishmentType.MUTES, T0.plusSeconds(61)).isEmpty());
        assertEquals(1, cache.expired(PLAYER, PunishmentType.MUTES, T0.plusSeconds(61)).size());
        assertTrue(cache.active(PLAYER, EnumSet.of(PunishmentType.WARN), T0).isEmpty(), "warnings are not cached");

        cache.add(of(3, PunishmentType.SHADOWMUTE, null));
        Punishment mute = cache.active(PLAYER, PunishmentType.MUTES, T0).orElseThrow();
        assertEquals(3, mute.id());
        assertTrue(cache.expired(PLAYER, PunishmentType.MUTES, T0.plusSeconds(61)).isEmpty(), "old mute replaced");
        assertTrue(MuteInfo.of(mute).shadow());

        cache.remove(PLAYER, List.of(3L));
        assertTrue(cache.active(PLAYER, PunishmentType.MUTES, T0).isEmpty());
        cache.evict(PLAYER);
        assertFalse(cache.contains(PLAYER));
    }

    @Test
    void requestValidation() {
        PunishmentRequest base = PunishmentRequest.of(PunishmentType.BAN, PLAYER, "Steve");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> base.withDuration(Duration.ZERO));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new PunishmentRequest(PunishmentType.MUTE, null, null, null, null, "x", "", null, false, null));
        new PunishmentRequest(PunishmentType.IPBAN, null, null, "1.2.3.4", null, "x", "", null, false, null);
    }
}
