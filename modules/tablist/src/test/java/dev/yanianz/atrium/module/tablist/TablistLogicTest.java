package dev.yanianz.atrium.module.tablist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TablistLogicTest {
    @Test
    void groupOrderPutsFirstGroupHighest() {
        assertEquals(3, TabOrder.groupOrder(0, 3));
        assertEquals(1, TabOrder.groupOrder(2, 3));
        assertEquals(0, TabOrder.groupOrder(3, 3), "no group");
        assertEquals(0, TabOrder.groupOrder(99, 3));
    }

    @Test
    void sortByGroupThenNameIgnoringCase() {
        UUID admin = UUID.randomUUID();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID guest = UUID.randomUUID();
        Map<UUID, Integer> orders = TabOrder.byGroupAndName(List.of(
                new TabOrder.Entry(guest, 2, "Aaron"),
                new TabOrder.Entry(bob, 1, "bob"),
                new TabOrder.Entry(alice, 1, "Alice"),
                new TabOrder.Entry(admin, 0, "Zed")));
        assertEquals(4, orders.get(admin));
        assertEquals(3, orders.get(alice));
        assertEquals(2, orders.get(bob));
        assertEquals(1, orders.get(guest));
    }

    @Test
    void settingsPickFirstMatchingGroupAndOverride() {
        TablistConfig config = new TablistConfig();
        List<String> problems = new ArrayList<>();
        TablistSettings s = TablistSettings.from(config, (o, p) -> problems.add(o));
        assertTrue(problems.isEmpty(), problems.toString());
        Set<String> perms = Set.of("atrium.tablist.group.vip", "atrium.tablist.group.moderator");
        int index = s.groupIndex(perms::contains);
        assertEquals(1, index, "moderator is listed before vip");
        assertTrue(s.listNameFormat(index).contains("[Mod]"));
        assertEquals(s.defaultListName(), s.listNameFormat(s.groupIndex(p -> false)));

        TablistSettings.Texts nether = s.texts("WORLD_NETHER", p -> false);
        assertTrue(nether.header().contains("Nether"));
        assertEquals(s.footer(), nether.footer(), "omitted footer keeps the default");
        assertEquals(s.header(), s.texts("world", p -> false).header());
    }

    @Test
    void invalidValuesAreClampedAndReported() {
        TablistConfig config = new TablistConfig();
        config.updateIntervalTicks = 0;
        TablistConfig.Group noPermission = new TablistConfig.Group();
        noPermission.name = "everyone";
        config.groups.add(noPermission);
        List<String> problems = new ArrayList<>();
        TablistSettings s = TablistSettings.from(config, (o, p) -> problems.add(o));
        assertEquals(2, s.intervalTicks());
        assertEquals(3, s.groups().size());
        assertEquals(List.of("update-interval-ticks", "groups.everyone"), problems);
    }
}
