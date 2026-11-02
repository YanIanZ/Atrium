package dev.yanianz.atrium.module.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.moderation.Punishment;
import dev.yanianz.atrium.api.moderation.PunishmentType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
class HistoryViewTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static Punishment of(long id, PunishmentType type, Instant expires, Instant revokedAt) {
        return new Punishment(id, type, PLAYER, "Steve", null, null, "Console", "r", T0, expires,
                revokedAt == null ? null : UUID.randomUUID(), revokedAt == null ? null : "Admin", revokedAt, null);
    }

    @Test
    void statesFollowTimeAndRevocation() {
        assertEquals(HistoryView.State.ACTIVE, HistoryView.state(of(1, PunishmentType.TEMPBAN, T0.plusSeconds(60), null), T0));
        assertEquals(HistoryView.State.EXPIRED,
                HistoryView.state(of(2, PunishmentType.TEMPBAN, T0.plusSeconds(60), null), T0.plusSeconds(60)));
        assertEquals(HistoryView.State.REVOKED,
                HistoryView.state(of(3, PunishmentType.BAN, null, T0.plusSeconds(5)), T0.plusSeconds(10)));
        assertEquals(HistoryView.State.INSTANT, HistoryView.state(of(4, PunishmentType.KICK, null, null), T0));
    }

    @Test
    void onlyRecordsStillInEffectCanBeRevoked() {
        assertTrue(HistoryView.revocable(of(1, PunishmentType.BAN, null, null), T0));
        assertTrue(HistoryView.revocable(of(2, PunishmentType.TEMPMUTE, T0.plusSeconds(60), null), T0));
        assertTrue(HistoryView.revocable(of(3, PunishmentType.WARN, T0.plusSeconds(60), null), T0));
        assertFalse(HistoryView.revocable(of(4, PunishmentType.KICK, null, null), T0), "kicks are history only");
        assertFalse(HistoryView.revocable(of(5, PunishmentType.TEMPBAN, T0.plusSeconds(60), null), T0.plusSeconds(60)),
                "expired rows cannot be lifted");
        assertFalse(HistoryView.revocable(of(6, PunishmentType.BAN, null, T0.plusSeconds(5)), T0.plusSeconds(10)),
                "already lifted rows cannot be lifted again");
    }

    @Test
    void liftingARecordRequiresThePermissionOfItsOwnCommand() {
        // /history revoke must not let a viewer lift a punishment they could not lift with its own command.
        String perm = ModerationCommands.PERM;
        assertEquals(perm + "unban", HistoryView.revokePermission(PunishmentType.BAN));
        assertEquals(perm + "unban", HistoryView.revokePermission(PunishmentType.TEMPBAN));
        assertEquals(perm + "unmute", HistoryView.revokePermission(PunishmentType.MUTE));
        assertEquals(perm + "unmute", HistoryView.revokePermission(PunishmentType.TEMPMUTE));
        assertEquals(perm + "unmute", HistoryView.revokePermission(PunishmentType.SHADOWMUTE));
        assertEquals(perm + "unjail", HistoryView.revokePermission(PunishmentType.JAIL));
        assertEquals(perm + "unbanip", HistoryView.revokePermission(PunishmentType.IPBAN));
        assertEquals(perm + "freeze", HistoryView.revokePermission(PunishmentType.FREEZE));
        assertEquals(perm + "delwarn", HistoryView.revokePermission(PunishmentType.WARN));
        assertNull(HistoryView.revokePermission(PunishmentType.KICK), "a kick has no revoke command");
    }

    @Test
    void everyTypeHasAColourKey() {
        for (PunishmentType type : PunishmentType.values()) {
            assertEquals("moderation.type." + type.name().toLowerCase(java.util.Locale.ROOT),
                    HistoryView.typeKey(type));
        }
        assertEquals("moderation.status.active", HistoryView.State.ACTIVE.messageKey());
        assertEquals("moderation.status.expired", HistoryView.State.EXPIRED.messageKey());
        assertEquals("moderation.status.revoked", HistoryView.State.REVOKED.messageKey());
    }

    @Test
    void historyPagesClampToTheLastPage() {
        Pagination page = Pagination.of(21, 99, 8);
        assertEquals(3, page.pages());
        assertEquals(3, page.current());
        assertEquals(16, page.offset());
        assertFalse(page.empty());

        Pagination empty = Pagination.of(0, 1, 8);
        assertTrue(empty.empty());
        assertEquals(0, empty.offset());
    }
}
