package dev.yanianz.atrium.module.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Decision table of /spawn, the first join and the respawn's spawn token. */
class SpawnResolverTest {
    private static final StoredLocation MAIN = new StoredLocation("ssb_0a1b_normal", 10.5, 64, -3.5, 90f, 0f);
    private static final StoredLocation FIRST = new StoredLocation("world", 1, 70, 1, 0f, 0f);
    private static final StoredLocation NETHER = new StoredLocation("world_nether", 5, 40, 5, 0f, 0f);
    private static final StoredLocation VIP = new StoredLocation("world", 100, 80, 100, 0f, 0f);
    private static final StoredLocation ADMIN = new StoredLocation("world", 200, 80, 200, 0f, 0f);

    private static Map<String, StoredLocation> spawns(boolean main, boolean first, boolean nether, boolean groups) {
        Map<String, StoredLocation> out = new HashMap<>();
        if (main) {
            out.put(TeleportStore.DEFAULT_SPAWN, MAIN);
        }
        if (first) {
            out.put(TeleportStore.FIRST_JOIN_SPAWN, FIRST);
        }
        if (nether) {
            out.put(TeleportStore.worldSpawnId("world_nether"), NETHER);
        }
        if (groups) {
            out.put(TeleportStore.groupSpawnId("VIP"), VIP);
            out.put(TeleportStore.groupSpawnId("admin"), ADMIN);
        }
        return out;
    }

    /** With the opt-in options switched on (per-world and group spawns). */
    private static SpawnResolver.Choice command(Map<String, StoredLocation> spawns, boolean perWorld, String world,
            Set<String> groups) {
        return SpawnResolver.forPlayer(spawns, perWorld, true, world, groups::contains, List.of());
    }

    /** The defaults: one global spawn, per-world and group spawns off. */
    private static SpawnResolver.Choice defaults(Map<String, StoredLocation> spawns, String world, Set<String> groups) {
        return SpawnResolver.forPlayer(spawns, false, false, world, groups::contains, List.of());
    }

    @Test
    void byDefaultOneGlobalMainSpawnIgnoresStoredWorldAndGroupSpawns() {
        // Old per-world and group entries stay in storage but have no effect while the options are off.
        Map<String, StoredLocation> all = spawns(true, true, true, true);
        for (String world : List.of("world", "world_nether", "ssb_0a1b_normal", "ssb_ffff_normal")) {
            SpawnResolver.Choice choice = defaults(all, world, Set.of("vip", "admin"));
            assertEquals(SpawnResolver.Source.DEFAULT, choice.source(), world);
            assertEquals(MAIN, choice.location(), world);
        }
        SpawnResolver.Choice none = defaults(Map.of(TeleportStore.worldSpawnId("world"), NETHER), "world", Set.of());
        assertEquals(SpawnResolver.Source.VANILLA, none.source());
        assertNull(none.key(), "without a main spawn: the first world's vanilla spawn, never a per-world entry");
    }

    @Test
    void importedMainSpawnIsUsedInEveryWorldWithoutAnOwnSpawn() {
        Map<String, StoredLocation> all = spawns(true, false, true, false);
        for (String world : List.of("world", "ssb_0a1b_normal", "ssb_ffff_normal", "world_the_end")) {
            SpawnResolver.Choice choice = command(all, true, world, Set.of());
            assertEquals(SpawnResolver.Source.DEFAULT, choice.source(), world);
            assertEquals(MAIN, choice.location());
        }
    }

    @Test
    void anExplicitPerWorldSpawnWinsInItsOwnWorldOnly() {
        Map<String, StoredLocation> all = spawns(true, false, true, true);
        SpawnResolver.Choice nether = command(all, true, "world_nether", Set.of("vip"));
        assertEquals(SpawnResolver.Source.WORLD, nether.source());
        assertEquals(NETHER, nether.location());
        assertEquals("world_nether", nether.key());
        // per-world spawns switched off: the world spawn is ignored
        assertEquals(SpawnResolver.Source.DEFAULT, command(all, false, "world_nether", Set.of()).source());
    }

    @Test
    void groupSpawnBeatsTheMainSpawnForMembers() {
        Map<String, StoredLocation> all = spawns(true, false, false, true);
        SpawnResolver.Choice vip = command(all, true, "world", Set.of("vip"));
        assertEquals(SpawnResolver.Source.GROUP, vip.source());
        assertEquals("vip", vip.key());
        assertEquals(VIP, vip.location());
        assertEquals(SpawnResolver.Source.DEFAULT, command(all, true, "world", Set.of()).source());
    }

    @Test
    void severalGroupsUseTheConfiguredOrderThenAlphabetical() {
        Map<String, StoredLocation> all = spawns(true, false, false, true);
        Set<String> both = Set.of("vip", "admin");
        assertEquals("admin", SpawnResolver.forPlayer(all, true, true, "world", both::contains, List.of()).key());
        assertEquals("vip", SpawnResolver.forPlayer(all, true, true, "world", both::contains, List.of("VIP")).key());
        assertEquals(List.of("vip", "admin", "x"), SpawnResolver.groupOrder(List.of("x", "admin", "vip"), List.of("vip")));
    }

    @Test
    void nothingStoredFallsBackToTheVanillaSpawn() {
        SpawnResolver.Choice perWorld = command(Map.of(), true, "world_nether", Set.of());
        assertEquals(SpawnResolver.Source.VANILLA, perWorld.source());
        assertEquals("world_nether", perWorld.key());
        assertFalse(perWorld.stored());
        SpawnResolver.Choice global = command(Map.of(), false, "world_nether", Set.of());
        assertNull(global.key(), "without per-world spawns the first world's spawn is used");
    }

    @Test
    void firstJoinUsesTheFirstJoinSpawnThenTheMainSpawn() {
        assertEquals(FIRST, SpawnResolver.forFirstJoin(spawns(true, true, false, false)).location());
        SpawnResolver.Choice main = SpawnResolver.forFirstJoin(spawns(true, false, true, true));
        assertEquals(SpawnResolver.Source.DEFAULT, main.source());
        assertEquals(MAIN, main.location());
        assertEquals(SpawnResolver.Source.VANILLA, SpawnResolver.forFirstJoin(Map.of()).source());
    }

    @Test
    void respawnSpawnTokenFollowsTheSameChoice() {
        // The respawn order picks "spawn" before "worldspawn" when a stored spawn exists for the player.
        Map<String, StoredLocation> all = spawns(true, false, false, true);
        SpawnResolver.Choice choice = command(all, true, "world", Set.of("vip"));
        Optional<String> token = RespawnPriority.choose(List.of("anchor", "bed", "spawn", "home", "worldspawn"),
                t -> t.equals("worldspawn") || (t.equals("spawn") && choice.stored()));
        assertEquals(Optional.of("spawn"), token);
        assertEquals(VIP, choice.location());
    }

    @Test
    void prefixesSplitTheStoreIntoWorldAndGroupSpawns() {
        Map<String, StoredLocation> all = spawns(true, true, true, true);
        assertEquals(Map.of("world_nether", NETHER), SpawnResolver.withPrefix(all, TeleportStore.WORLD_PREFIX));
        assertEquals(Set.of("vip", "admin"), SpawnResolver.withPrefix(all, TeleportStore.GROUP_PREFIX).keySet());
        assertTrue(SpawnResolver.groupPermission("VIP").equals("atrium.spawn.group.vip"));
    }
}
