package dev.yanianz.atrium.module.sourbycraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.profile.ProfileWorldRef;
import dev.yanianz.atrium.api.profile.ProfileWorldService;
import dev.yanianz.atrium.api.world.KnownWorld;
import dev.yanianz.atrium.api.world.WorldProvider;
import dev.yanianz.atrium.api.world.WorldResolver.Status;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

class AwfWorldProviderTest {
    private final AwfWorldResolverTest.FakeBackend backend = new AwfWorldResolverTest.FakeBackend();
    private final AwfWorldResolver resolver = new AwfWorldResolver(backend,
            () -> new AwfWorldResolver.Settings(true, Duration.ofSeconds(5), Set.of()), NOPLogger.NOP_LOGGER);

    private AwfWorldProvider provider(Collection<String> names, Location spawn) {
        return new AwfWorldProvider(new AwfWorldProvider.Catalog() {
            @Override
            public Collection<String> list() {
                return names;
            }

            @Override
            public World loadedWorld(String name) {
                return backend.loaded.get(name);
            }

            @Override
            public Location spawn(World world) {
                return spawn;
            }
        }, resolver, NOPLogger.NOP_LOGGER);
    }

    @Test
    void listsAwfWorldsWithTheirLoadedState() {
        backend.loaded.put("hub", AwfWorldResolverTest.world("hub"));
        AwfWorldProvider awf = provider(List.of("hub", "p_1"), null);
        assertEquals(WorldProvider.AWF, awf.id());
        List<KnownWorld> worlds = List.copyOf(awf.knownWorlds());
        assertEquals(2, worlds.size());
        assertTrue(worlds.get(0).loaded());
        assertFalse(worlds.get(1).loaded());
        assertEquals("p_1", awf.canonicalName("P_1").orElseThrow());
    }

    @Test
    void loadsThroughTheResolverAndFallsBackToTheWorldSpawn() throws Exception {
        backend.managed.add("p_1");
        AwfWorldProvider awf = provider(List.of("p_1"), null);
        var lookup = awf.load("p_1");
        World world = AwfWorldResolverTest.world("p_1");
        backend.loads.get("p_1").complete(world);
        assertEquals(Status.LOADED_ON_DEMAND, lookup.get(1, TimeUnit.SECONDS).status());
        assertTrue(awf.spawn(world).isEmpty());
        Location stored = new Location(world, 10, 70, -3, 90, 0);
        assertEquals(stored, provider(List.of("p_1"), stored).spawn(world).orElseThrow());
    }

    @Test
    void profileWorldsKeepRolesAndOrder() {
        List<ProfileWorldService.ProfileWorld> worlds = ProfileWorldHook.map(List.of(
                new ProfileWorldRef("p_nether", "NETHER", false),
                new ProfileWorldRef("p_main", "PRIMARY", true)));
        ProfileWorldService.ProfileWorlds profile = new ProfileWorldService.ProfileWorlds(UUID.randomUUID(), worlds);
        assertEquals("p_main", profile.primary().orElseThrow().world());
        assertTrue(ProfileWorldHook.map(null).isEmpty());
    }
}
