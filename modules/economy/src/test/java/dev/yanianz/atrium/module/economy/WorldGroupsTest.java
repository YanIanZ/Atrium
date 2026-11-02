package dev.yanianz.atrium.module.economy;

import static org.junit.jupiter.api.Assertions.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorldGroupsTest {
    private static WorldGroups groups(boolean enabled, String defaultGroup, boolean cross) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("Survival", List.of("world", "world_nether", "world_the_end"));
        map.put("resource", List.of("resource_*", "mine?"));
        map.put("events", List.of("*_event", "resource_special"));
        return new WorldGroups(enabled, defaultGroup, map, cross);
    }

    @Test void exactNamesGlobsAndDefaultResolve() {
        WorldGroups groups = groups(true, "default", false);
        assertEquals("survival", groups.keyOfWorld("world"));
        assertEquals("survival", groups.keyOfWorld("WORLD_NETHER"), "World names are case-insensitive");
        assertEquals("resource", groups.keyOfWorld("resource_2"));
        assertEquals("resource", groups.keyOfWorld("mine1"));
        assertEquals(WorldGroups.DEFAULT_KEY, groups.keyOfWorld("mine12"), "? matches exactly one character");
        assertEquals("events", groups.keyOfWorld("halloween_event"));
        assertEquals("events", groups.keyOfWorld("resource_special"), "An exact name wins over an earlier glob");
        assertEquals(WorldGroups.DEFAULT_KEY, groups.keyOfWorld("lobby"), "Unlisted worlds use the default group");
        assertEquals(WorldGroups.DEFAULT_KEY, groups.keyOfWorld(null));
        assertEquals(List.of("default", "survival", "resource", "events"), groups.names());
    }

    @Test void globCharactersOtherThanStarAndQuestionMarkAreLiteral() {
        WorldGroups groups = new WorldGroups(true, "default", Map.of("dots", List.of("a.b*")), false);
        assertEquals("dots", groups.keyOfWorld("a.b_1"));
        assertEquals(WorldGroups.DEFAULT_KEY, groups.keyOfWorld("axb_1"));
    }

    @Test void renamedDefaultGroupKeepsTheFixedStorageKey() {
        WorldGroups groups = groups(true, "Main", false);
        assertEquals("main", groups.defaultName());
        assertEquals(WorldGroups.DEFAULT_KEY, groups.keyOf("main"));
        assertEquals(WorldGroups.DEFAULT_KEY, groups.keyOf("default"), "The storage key always names the default group");
        assertEquals("main", groups.display(WorldGroups.DEFAULT_KEY));
        assertEquals("survival", groups.keyOf("SURVIVAL"));
        assertNull(groups.keyOf("nope"));
    }

    @Test void disabledGroupsMapEveryWorldToTheDefault() {
        WorldGroups groups = groups(false, "default", false);
        assertFalse(groups.enabled());
        assertEquals(WorldGroups.DEFAULT_KEY, groups.keyOfWorld("world"));
        assertEquals(WorldGroups.DEFAULT_KEY, groups.keyOfWorld("resource_1"));
        assertEquals(List.of("default"), groups.keys());
        assertNull(groups.keyOf("survival"), "Configured but disabled groups cannot be chosen");
        assertTrue(groups.permits("survival", WorldGroups.DEFAULT_KEY), "Nothing is refused while groups are off");
    }

    @Test void invalidConfigurationsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new WorldGroups(true, "default",
                Map.of("a", List.of("world"), "b", List.of("world")), false), "A world in two groups is ambiguous");
        assertThrows(IllegalArgumentException.class, () -> new WorldGroups(true, "main",
                Map.of("default", List.of("world")), false), "'default' is reserved for the default group");
        assertThrows(IllegalArgumentException.class, () -> new WorldGroups(true, "default",
                Map.of("bad name", List.of("world")), false));
    }

    @Test void blankDefaultFallsBackToDefault() {
        assertEquals("default", new WorldGroups(true, " ", Map.of(), false).defaultName());
    }

    @Test void crossGroupPaymentsFollowTheSetting() {
        WorldGroups strict = groups(true, "default", false);
        assertTrue(strict.permits("survival", "survival"));
        assertFalse(strict.permits("survival", WorldGroups.DEFAULT_KEY));
        assertTrue(groups(true, "default", true).permits("survival", WorldGroups.DEFAULT_KEY));
    }

    @Test void receiverGroupPrefersExplicitThenOnlineThenPayer() {
        assertEquals("resource", WorldGroups.receiverGroup("survival", "resource", "events"));
        assertEquals("events", WorldGroups.receiverGroup("survival", null, "events"));
        assertEquals("survival", WorldGroups.receiverGroup("survival", null, null), "An offline receiver is paid in the payer's group");
    }

    @Test void configDefaultsKeepGroupsOff() {
        EconomyConfig config = new EconomyConfig();
        WorldGroups groups = WorldGroups.from(config.groups);
        assertFalse(groups.enabled());
        assertFalse(config.groups.allowCrossGroupPay);
        assertEquals("default", groups.defaultName());
        assertEquals(WorldGroups.DEFAULT_KEY, groups.keyOfWorld("creative"));
    }
}
