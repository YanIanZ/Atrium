package dev.yanianz.atrium.module.worlds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.world.KnownWorld;
import dev.yanianz.atrium.api.world.WorldProvider;
import dev.yanianz.atrium.api.world.WorldResolver.Resolution;
import dev.yanianz.atrium.api.world.WorldResolver.Status;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

class WorldProviderChainTest {
    private final Map<String, World> loaded = new HashMap<>();
    private WorldProviderChain.Settings settings =
            new WorldProviderChain.Settings(List.of("multiverse", "awf", "slime", "bukkit"), true, Duration.ofSeconds(5));
    private final WorldProviderChain chain = new WorldProviderChain(() -> settings,
            name -> loaded.get(name.toLowerCase()), NOPLogger.NOP_LOGGER);

    @Test
    void providersFollowTheConfiguredOrderAndUnlistedOnesComeLast() {
        FakeProvider bukkit = new FakeProvider("bukkit");
        FakeProvider custom = new FakeProvider("custom");
        FakeProvider slime = new FakeProvider("slime");
        FakeProvider mv = new FakeProvider("multiverse");
        chain.register(bukkit);
        chain.register(custom);
        chain.register(slime);
        chain.register(mv);
        assertEquals(List.of("multiverse", "slime", "bukkit", "custom"), ids(chain.providers()));

        settings = new WorldProviderChain.Settings(List.of("Bukkit", " slime "), true, Duration.ofSeconds(5));
        assertEquals(List.of("bukkit", "slime", "custom", "multiverse"), ids(chain.providers()));
    }

    @Test
    void theFirstProviderInOrderThatKnowsTheWorldOwnsIt() {
        FakeProvider bukkit = new FakeProvider("bukkit", "lobby");
        FakeProvider mv = new FakeProvider("multiverse", "lobby", "survival");
        chain.register(bukkit);
        chain.register(mv);
        assertSame(mv, chain.find("lobby").orElseThrow().provider());
        settings = new WorldProviderChain.Settings(List.of("bukkit", "multiverse"), true, Duration.ofSeconds(5));
        assertSame(bukkit, chain.find("lobby").orElseThrow().provider());
        assertSame(mv, chain.find("survival").orElseThrow().provider());
        assertTrue(chain.find("nowhere").isEmpty());
    }

    @Test
    void aliasesResolveToTheCanonicalName() {
        FakeProvider mv = new FakeProvider("multiverse").alias("survival_1", "Survival");
        chain.register(mv);
        assertEquals("survival_1", chain.find("survival").orElseThrow().world());
        assertEquals("survival_1", chain.find("SURVIVAL_1").orElseThrow().world());
        // An exact name of another world wins over an alias.
        mv.worlds.add(KnownWorld.of("Survival", "multiverse", false));
        assertEquals("Survival", chain.find("Survival").orElseThrow().world());
    }

    @Test
    void aFailingProviderIsSkipped() {
        FakeProvider broken = new FakeProvider("multiverse", "lobby");
        broken.failListing = true;
        FakeProvider bukkit = new FakeProvider("bukkit", "lobby");
        chain.register(broken);
        chain.register(bukkit);
        assertSame(bukkit, chain.find("lobby").orElseThrow().provider());
        assertEquals(1, chain.worlds().size());
    }

    @Test
    void worldsAreListedOnceAndTheFirstProviderWins() {
        chain.register(new FakeProvider("bukkit", "world", "Lobby"));
        chain.register(new FakeProvider("multiverse", "lobby", "arena"));
        List<KnownWorld> worlds = chain.worlds();
        assertEquals(3, worlds.size());
        KnownWorld lobby = worlds.stream().filter(w -> w.name().equalsIgnoreCase("lobby")).findFirst().orElseThrow();
        assertEquals("multiverse", lobby.provider());
    }

    @Test
    void registeringTheSameIdTwiceFails() {
        chain.register(new FakeProvider("slime"));
        assertThrows(IllegalStateException.class, () -> chain.register(new FakeProvider("SLIME")));
        FakeProvider awf = new FakeProvider("awf");
        chain.register(awf);
        chain.unregister(awf);
        assertTrue(chain.provider("awf").isEmpty());
    }

    @Test
    void aLoadedWorldIsReturnedWithoutLoading() throws Exception {
        FakeProvider mv = new FakeProvider("multiverse", "lobby");
        chain.register(mv);
        loaded.put("lobby", FakeProvider.world("lobby"));
        Resolution resolution = chain.lookup("lobby").get(1, TimeUnit.SECONDS);
        assertEquals(Status.LOADED, resolution.status());
        assertEquals(0, mv.loadCalls.get());
    }

    @Test
    void anUnknownWorldIsNotFound() throws Exception {
        chain.register(new FakeProvider("multiverse", "lobby"));
        assertEquals(Status.NOT_FOUND, chain.lookup("nowhere").get(1, TimeUnit.SECONDS).status());
        // Loaded but unknown to every provider: still found (plain Bukkit fallback).
        loaded.put("other", FakeProvider.world("other"));
        assertEquals(Status.LOADED, chain.lookup("other").get(1, TimeUnit.SECONDS).status());
    }

    @Test
    void anUnloadedWorldIsLoadedOnceForConcurrentLookups() throws Exception {
        FakeProvider slime = new FakeProvider("slime", "arena");
        chain.register(slime);
        CompletableFuture<Resolution> first = chain.lookup("arena");
        CompletableFuture<Resolution> second = chain.lookup("ARENA");
        assertFalse(first.isDone());
        assertSame(first, second);
        assertEquals(1, slime.loadCalls.get());
        World arena = FakeProvider.world("arena");
        slime.loads.get("arena").complete(Resolution.loaded(arena));
        Resolution resolution = first.get(1, TimeUnit.SECONDS);
        assertEquals(Status.LOADED_ON_DEMAND, resolution.status());
        assertSame(arena, resolution.world());
        assertEquals(0, chain.inFlightCount());
    }

    @Test
    void loadingOnDemandCanBeTurnedOff() throws Exception {
        FakeProvider slime = new FakeProvider("slime", "arena");
        chain.register(slime);
        settings = new WorldProviderChain.Settings(List.of(), false, Duration.ofSeconds(5));
        assertEquals(Status.NOT_ALLOWED, chain.lookup("arena").get(1, TimeUnit.SECONDS).status());
        assertEquals(0, slime.loadCalls.get());
    }

    @Test
    void aSlowLoadTimesOutAndAFailedLoadIsReported() throws Exception {
        FakeProvider slime = new FakeProvider("slime", "slow", "broken");
        chain.register(slime);
        settings = new WorldProviderChain.Settings(List.of(), true, Duration.ofMillis(50));
        assertEquals(Status.TIMEOUT, chain.lookup("slow").get(2, TimeUnit.SECONDS).status());
        CompletableFuture<Resolution> broken = chain.lookup("broken");
        slime.loads.get("broken").completeExceptionally(new IllegalStateException("corrupt"));
        Resolution resolution = broken.get(1, TimeUnit.SECONDS);
        assertEquals(Status.FAILED, resolution.status());
        assertEquals("corrupt", resolution.detail());
    }

    @Test
    void aProfileWorldLoadsThroughTheGivenProvider() throws Exception {
        FakeProvider mv = new FakeProvider("multiverse", "p_world");
        FakeProvider awf = new FakeProvider("awf", "p_world");
        chain.register(mv);
        chain.register(awf);
        CompletableFuture<Resolution> lookup = chain.load(awf, "p_world");
        assertEquals(0, mv.loadCalls.get());
        assertEquals(1, awf.loadCalls.get());
        awf.loads.get("p_world").complete(Resolution.failed("p_world", Status.BUSY, "unloading"));
        assertEquals(Status.BUSY, lookup.get(1, TimeUnit.SECONDS).status());
    }

    private static List<String> ids(List<WorldProvider> providers) {
        return providers.stream().map(WorldProvider::id).toList();
    }
}
