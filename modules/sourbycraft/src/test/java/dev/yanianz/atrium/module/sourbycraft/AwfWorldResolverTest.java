package dev.yanianz.atrium.module.sourbycraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.world.WorldResolver;
import dev.yanianz.atrium.api.world.WorldResolver.Status;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

class AwfWorldResolverTest {
    static World world(String name) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "hashCode" -> name.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> "World[" + name + "]";
                    default -> null;
                });
    }

    /** Fake AWF: loaded worlds, managed worlds, and pending loads completed by the test. */
    static class FakeBackend implements AwfWorldResolver.Backend {
        final Map<String, World> loaded = new HashMap<>();
        final Set<String> managed = new HashSet<>();
        final Map<String, CompletableFuture<World>> loads = new HashMap<>();
        final AtomicInteger loadCalls = new AtomicInteger();

        @Override
        public World loadedWorld(String name) {
            return loaded.get(name);
        }

        @Override
        public boolean managed(String name) {
            return managed.contains(name);
        }

        @Override
        public CompletableFuture<World> load(String name) {
            loadCalls.incrementAndGet();
            return loads.computeIfAbsent(name, n -> new CompletableFuture<>());
        }

        @Override
        public boolean busy(Throwable error) {
            return error instanceof IllegalStateException && String.valueOf(error.getMessage()).contains("busy");
        }
    }

    private final FakeBackend backend = new FakeBackend();
    private AwfWorldResolver.Settings settings = new AwfWorldResolver.Settings(true, Duration.ofSeconds(5), Set.of());
    private final AwfWorldResolver resolver = new AwfWorldResolver(backend, () -> settings, NOPLogger.NOP_LOGGER);

    private static WorldResolver.Resolution done(CompletableFuture<WorldResolver.Resolution> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    @Test
    void loadedWorldResolvesAtOnce() throws Exception {
        World w = world("world");
        backend.loaded.put("world", w);
        CompletableFuture<WorldResolver.Resolution> future = resolver.lookup("world");
        assertTrue(future.isDone());
        assertEquals(Status.LOADED, done(future).status());
        assertSame(w, done(future).world());
        assertEquals(0, backend.loadCalls.get());
    }

    @Test
    void unknownWorldIsNotFoundWithoutLoading() throws Exception {
        assertEquals(Status.NOT_FOUND, done(resolver.lookup("nether_old")).status());
        assertEquals(Status.NOT_FOUND, done(resolver.lookup("")).status());
        assertEquals(0, backend.loadCalls.get());
        assertTrue(resolver.resolve("nether_old").get().isEmpty());
    }

    @Test
    void awfWorldLoadsOnDemandAndConcurrentLookupsShareTheLoad() throws Exception {
        backend.managed.add("island_1");
        CompletableFuture<WorldResolver.Resolution> first = resolver.lookup("island_1");
        CompletableFuture<WorldResolver.Resolution> second = resolver.lookup("island_1");
        assertFalse(first.isDone());
        assertSame(first, second);
        assertEquals(1, backend.loadCalls.get());
        World w = world("island_1");
        backend.loads.get("island_1").complete(w);
        WorldResolver.Resolution resolution = done(first);
        assertEquals(Status.LOADED_ON_DEMAND, resolution.status());
        assertSame(w, resolution.world());
        assertEquals(0, resolver.inFlightCount());
    }

    @Test
    void onDemandDisabledOrDenied() throws Exception {
        backend.managed.add("island_1");
        backend.managed.add("template_work");
        settings = new AwfWorldResolver.Settings(true, Duration.ofSeconds(5), Set.of("template_work"));
        assertEquals(Status.NOT_ALLOWED, done(resolver.lookup("template_work")).status());
        settings = new AwfWorldResolver.Settings(false, Duration.ofSeconds(5), Set.of());
        assertEquals(Status.NOT_ALLOWED, done(resolver.lookup("island_1")).status());
        assertEquals(0, backend.loadCalls.get());
    }

    @Test
    void busyFailedAndTimeout() throws Exception {
        backend.managed.add("busy");
        backend.managed.add("broken");
        backend.managed.add("slow");
        backend.loads.put("busy", CompletableFuture.failedFuture(new IllegalStateException("busy with unload")));
        backend.loads.put("broken", CompletableFuture.failedFuture(new RuntimeException("corrupt store")));
        assertEquals(Status.BUSY, done(resolver.lookup("busy")).status());
        WorldResolver.Resolution broken = done(resolver.lookup("broken"));
        assertEquals(Status.FAILED, broken.status());
        assertTrue(broken.detail().contains("corrupt"));

        settings = new AwfWorldResolver.Settings(true, Duration.ofMillis(50), Set.of());
        WorldResolver.Resolution slow = done(resolver.lookup("slow"));
        assertEquals(Status.TIMEOUT, slow.status());
        assertFalse(backend.loads.get("slow").isDone(), "the timeout never completes the platform's own future");
        assertEquals(0, resolver.inFlightCount());
    }

    @Test
    void throwingBackendIsFailedNotAnException() throws Exception {
        AwfWorldResolver.Backend throwing = new FakeBackend() {
            @Override
            public boolean managed(String name) {
                throw new IllegalStateException("registry closed");
            }
        };
        AwfWorldResolver r = new AwfWorldResolver(throwing, () -> settings, NOPLogger.NOP_LOGGER);
        assertEquals(Status.FAILED, done(r.lookup("x")).status());
    }

    @Test
    void defaultBukkitResolverStatusesAreConsistent() {
        assertTrue(Status.LOADED.available());
        assertTrue(Status.LOADED_ON_DEMAND.available());
        for (Status status : Status.values()) {
            if (status != Status.LOADED && status != Status.LOADED_ON_DEMAND) {
                assertFalse(status.available(), status.name());
            }
        }
    }
}
