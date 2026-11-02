package dev.yanianz.atrium.module.worlds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.profile.ProfileWorldService.ProfileWorld;
import dev.yanianz.atrium.api.profile.ProfileWorldService.ProfileWorlds;
import dev.yanianz.atrium.api.world.KnownWorld;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class WorldRulesTest {
    /** Permissions as a permission plugin would answer: set nodes with a value, everything else unset. */
    static WorldAccess.Perms perms(Map<String, Boolean> set) {
        return new WorldAccess.Perms() {
            @Override
            public boolean has(String node) {
                return set.getOrDefault(node, false);
            }

            @Override
            public boolean isSet(String node) {
                return set.containsKey(node);
            }
        };
    }

    @Test
    void worldNamesAreValidated() {
        for (String ok : List.of("world", "world_nether", "Survival-2", "a.b", "x+y", "w".repeat(64))) {
            assertTrue(WorldNames.valid(ok), ok);
        }
        for (String bad : List.of("", "..", "../world", "a/b", "a\\b", ".hidden", "a..b", "with space", "w".repeat(65),
                "c:", "%2e%2e")) {
            assertFalse(WorldNames.valid(bad), bad);
        }
        assertFalse(WorldNames.valid(null));
    }

    @Test
    void fileLoaderPathsStayInsideTheDirectory() {
        Path dir = Path.of("/srv/mc/slime_worlds");
        assertEquals(dir.resolve("arena.slime"), WorldNames.fileIn(dir, "arena", ".slime"));
        assertNull(WorldNames.fileIn(dir, "../server", ".slime"));
        assertNull(WorldNames.fileIn(dir, "..", ".slime"));
        assertNull(WorldNames.fileIn(dir, "a/b", ".slime"));
        assertNotNull(WorldNames.fileIn(Path.of("relative/dir"), "ok", ".slime"));
    }

    @Test
    void perWorldPermissionRules() {
        // Open mode: atrium.wtp is enough, an explicit deny still blocks.
        assertTrue(WorldAccess.mayUse(perms(Map.of()), "Lobby", false));
        assertFalse(WorldAccess.mayUse(perms(Map.of("atrium.wtp.world.lobby", false)), "Lobby", false));
        // Strict mode: the world's own node or the wildcard.
        assertFalse(WorldAccess.mayUse(perms(Map.of()), "Lobby", true));
        assertTrue(WorldAccess.mayUse(perms(Map.of("atrium.wtp.world.lobby", true)), "Lobby", true));
        assertTrue(WorldAccess.mayUse(perms(Map.of("atrium.wtp.world.*", true)), "arena", true));
        // An explicit deny beats the wildcard.
        assertFalse(WorldAccess.mayUse(perms(Map.of("atrium.wtp.world.*", true, "atrium.wtp.world.arena", false)),
                "Arena", true));
        assertEquals("atrium.wtp.world.my_world", WorldAccess.worldNode("My_World"));
    }

    @Test
    void tabCompletionIsPrefixAndPermissionFiltered() {
        List<KnownWorld> worlds = List.of(
                KnownWorld.of("world", "bukkit", true),
                KnownWorld.of("world_nether", "bukkit", true),
                KnownWorld.of("World_Old", "multiverse", false),
                new KnownWorld("survival_1", "multiverse", false, "Survival", null),
                new KnownWorld("hub", "multiverse", true, "Main Hub", null),
                KnownWorld.of("bad name", "multiverse", false),
                KnownWorld.of("arena", "slime", false));
        assertEquals(List.of("world", "world_nether", "World_Old"), WorldSuggestions.filter(worlds, "wor", w -> true));
        assertEquals(List.of("world", "World_Old"),
                WorldSuggestions.filter(worlds, "WOR", w -> !w.equals("world_nether")));
        // Single-word aliases are offered too; aliases with spaces and invalid names are not.
        assertEquals(List.of("Survival", "survival_1"), WorldSuggestions.filter(worlds, "s", w -> true));
        assertEquals(List.of("hub"), WorldSuggestions.filter(worlds, "h", w -> true));
        assertFalse(WorldSuggestions.filter(worlds, "", w -> true).contains("bad name"));
        assertEquals(7, WorldSuggestions.filter(worlds, "", w -> true).size());
        // Permission filtering uses the world name, also for its alias.
        assertEquals(List.of(), WorldSuggestions.filter(worlds, "surv", w -> !w.equals("survival_1")));
    }

    @Test
    void pagesAreClamped() {
        List<Integer> all = List.of(1, 2, 3, 4, 5, 6, 7);
        assertEquals(3, WorldSuggestions.pageCount(7, 3));
        assertEquals(1, WorldSuggestions.pageCount(0, 3));
        assertEquals(List.of(4, 5, 6), WorldSuggestions.page(all, 2, 3));
        assertEquals(List.of(7), WorldSuggestions.page(all, 99, 3));
        assertEquals(List.of(1, 2, 3), WorldSuggestions.page(all, -1, 3));
    }

    @Test
    void theProfilePrimaryWorldIsTheFirstWithRolePrimary() {
        UUID id = UUID.randomUUID();
        ProfileWorlds worlds = new ProfileWorlds(id, List.of(
                new ProfileWorld("p_nether", "NETHER", false),
                new ProfileWorld("p_main", "primary", false),
                new ProfileWorld("p_main_2", "PRIMARY", true)));
        assertEquals("p_main", worlds.primary().orElseThrow().world());
        assertTrue(new ProfileWorlds(id, List.of(new ProfileWorld("shared", "SHARED", true))).primary().isEmpty());
        assertTrue(new ProfileWorlds(id, null).primary().isEmpty());
    }

    @Test
    void unloadNeedsAFreshConfirmationForTheSameWorld() {
        AtomicLong now = new AtomicLong(1_000);
        UnloadConfirmations confirmations = new UnloadConfirmations(now::get);
        assertFalse(confirmations.consume("admin", "arena"));
        confirmations.request("admin", "arena", 30_000);
        assertFalse(confirmations.consume("admin", "other"));
        // A wrong confirmation used the request up.
        assertFalse(confirmations.consume("admin", "arena"));
        confirmations.request("admin", "arena", 30_000);
        assertFalse(confirmations.consume("someone-else", "arena"));
        assertTrue(confirmations.consume("admin", "ARENA"));
        confirmations.request("admin", "arena", 30_000);
        now.addAndGet(30_001);
        assertFalse(confirmations.consume("admin", "arena"));
    }

    @Test
    void statusMessages() {
        Map<dev.yanianz.atrium.api.world.WorldResolver.Status, String> keys = new HashMap<>();
        for (var status : dev.yanianz.atrium.api.world.WorldResolver.Status.values()) {
            keys.put(status, WorldCommands.statusKey(status));
        }
        assertEquals("worlds.unknown", keys.get(dev.yanianz.atrium.api.world.WorldResolver.Status.NOT_FOUND));
        assertEquals("worlds.load-timeout", keys.get(dev.yanianz.atrium.api.world.WorldResolver.Status.TIMEOUT));
        assertEquals("worlds.load-busy", keys.get(dev.yanianz.atrium.api.world.WorldResolver.Status.BUSY));
    }
}
