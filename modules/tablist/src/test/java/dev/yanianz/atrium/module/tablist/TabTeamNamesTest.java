package dev.yanianz.atrium.module.tablist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TabTeamNamesTest {
    private static List<UUID> byTeam(Map<UUID, String> names) {
        List<UUID> ids = new ArrayList<>(names.keySet());
        ids.sort(Comparator.comparing(names::get));
        return ids;
    }

    @Test
    void teamNamesSortByGroupThenName() {
        UUID admin = UUID.randomUUID();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID guest = UUID.randomUUID();
        UUID al = UUID.randomUUID();
        Map<UUID, String> names = TabTeamNames.assign(List.of(
                new TabTeamNames.Member(guest, 3, "Aaron"),
                new TabTeamNames.Member(bob, 1, "bob"),
                new TabTeamNames.Member(alice, 1, "Alice"),
                new TabTeamNames.Member(al, 1, "Al"),
                new TabTeamNames.Member(admin, 0, "Zed")));
        assertEquals(List.of(admin, al, alice, bob, guest), byTeam(names));
    }

    @Test
    void namesAreDistinctFixedWidthAndAtMostSixteenCharacters() {
        List<TabTeamNames.Member> members = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            members.add(new TabTeamNames.Member(UUID.randomUUID(), i % 3, i % 2 == 0 ? "SameNameForEveryone" : "§c<b>Nick"));
        }
        Map<UUID, String> names = TabTeamNames.assign(members);
        assertEquals(50, new HashSet<>(names.values()).size());
        for (String name : names.values()) {
            assertEquals(TabTeamNames.MAX_LENGTH, name.length(), name);
            assertTrue(name.matches("[0-9]{2}[a-z0-9_.]{12}[0-9a-z]{2}"), name);
        }
    }

    @Test
    void disambiguatorIsStableAndKeysAreSanitised() {
        UUID a = new UUID(0, 1);
        UUID b = new UUID(0, 2);
        List<TabTeamNames.Member> members = List.of(new TabTeamNames.Member(b, 0, "Steve"),
                new TabTeamNames.Member(a, 0, "steve"));
        Map<UUID, String> first = TabTeamNames.assign(members);
        assertEquals("00steve.......00", first.get(a));
        assertEquals("00steve.......01", first.get(b));
        assertEquals(first, TabTeamNames.assign(List.of(members.get(1), members.get(0))));
        assertEquals("a_b_c____...", TabTeamNames.key("A b§c<>[]"));
        assertEquals("............", TabTeamNames.key(null));
        assertEquals("99", TabTeamNames.group(250));
        assertEquals("00", TabTeamNames.group(-1));
        assertEquals("0z", TabTeamNames.suffix(35));
        assertEquals("10", TabTeamNames.suffix(36));
    }
}
