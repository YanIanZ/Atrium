package dev.yanianz.atrium.module.seen;

import static org.junit.jupiter.api.Assertions.*;

import dev.yanianz.atrium.api.user.PlayerRef;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SeenFormatTest {
    @Test void formatsAbsoluteDatesInTheConfiguredZone() {
        var utc = SeenFormat.formatter("yyyy-MM-dd HH:mm", "UTC");
        var jakarta = SeenFormat.formatter("dd/MM/yyyy HH:mm", "Asia/Jakarta");
        Instant instant = Instant.parse("2026-01-02T03:04:05Z");
        assertEquals("2026-01-02 03:04", SeenFormat.absolute(instant, utc));
        assertEquals("02/01/2026 10:04", SeenFormat.absolute(instant, jakarta));
    }

    @Test void rejectsInvalidPatternOrZone() {
        assertThrows(IllegalArgumentException.class, () -> SeenFormat.formatter("yyyy-MM-dd {", "UTC"));
        assertThrows(IllegalArgumentException.class, () -> SeenFormat.formatter("yyyy", "Mars/Olympus"));
        assertDoesNotThrow(() -> SeenFormat.formatter("yyyy", ""));
    }

    @Test void sinceIsNeverNegative() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        assertEquals(Duration.ofMinutes(5), SeenFormat.since(now.minusSeconds(300), now));
        assertEquals(Duration.ZERO, SeenFormat.since(now.plusSeconds(30), now));
    }

    @Test void altsExcludeSelfAndAreSortedAndDistinct() {
        UUID self = UUID.randomUUID();
        List<PlayerRef> refs = List.of(new PlayerRef(self, "Steve"), new PlayerRef(UUID.randomUUID(), "zed"),
                new PlayerRef(UUID.randomUUID(), "Alex"), new PlayerRef(UUID.randomUUID(), "Alex"),
                new PlayerRef(UUID.randomUUID(), ""));
        assertEquals(List.of("Alex", "zed"), SeenFormat.otherNames(refs, self));
        assertEquals(List.of(), SeenFormat.otherNames(List.of(new PlayerRef(self, "Steve")), self));
    }

    @Test void groupsByFirstMatchingPermission() {
        var staff = new ListGroup("<red>Staff", "group.staff");
        var vip = new ListGroup("<gold>VIP", "group.vip");
        var everyone = new ListGroup("<gray>Players", "");
        Map<String, Set<String>> perms = Map.of(
                "admin", Set.of("group.staff", "group.vip"),
                "Bob", Set.of("group.vip"),
                "carl", Set.of(),
                "anna", Set.of());
        var groups = SeenFormat.group(List.of(staff, vip, everyone), List.of("carl", "admin", "Bob", "anna"),
                (p, node) -> perms.get(p).contains(node), p -> p);
        assertEquals(3, groups.size());
        assertSame(staff, groups.get(0).group());
        assertEquals(List.of("admin"), groups.get(0).members());
        assertEquals(List.of("Bob"), groups.get(1).members());
        assertEquals(List.of("anna", "carl"), groups.get(2).members());
    }

    @Test void unmatchedPlayersGoToOtherAndEmptyGroupsAreOmitted() {
        var staff = new ListGroup("<red>Staff", "group.staff");
        var groups = SeenFormat.group(List.of(staff), List.of("b", "A"), (p, node) -> false, p -> p);
        assertEquals(1, groups.size());
        assertNull(groups.getFirst().group());
        assertEquals(List.of("A", "b"), groups.getFirst().members());
        assertTrue(SeenFormat.group(List.of(staff), List.<String>of(), (p, n) -> true, p -> p).isEmpty());
    }
}
