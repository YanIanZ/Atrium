package dev.yanianz.atrium.module.worlds;

import dev.yanianz.atrium.api.world.KnownWorld;
import dev.yanianz.atrium.api.world.WorldProvider;
import dev.yanianz.atrium.api.world.WorldResolver.Resolution;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.World;

/** A world manager for tests: known worlds (with aliases) and loads the test completes. */
final class FakeProvider implements WorldProvider {
    final String id;
    final List<KnownWorld> worlds = new ArrayList<>();
    final Map<String, CompletableFuture<Resolution>> loads = new HashMap<>();
    final AtomicInteger loadCalls = new AtomicInteger();
    boolean failListing;

    FakeProvider(String id, String... names) {
        this.id = id;
        for (String name : names) {
            worlds.add(KnownWorld.of(name, id, false));
        }
    }

    FakeProvider alias(String name, String alias) {
        worlds.add(new KnownWorld(name, id, false, alias, null));
        return this;
    }

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

    @Override
    public String id() {
        return id;
    }

    @Override
    public List<KnownWorld> knownWorlds() {
        if (failListing) {
            throw new IllegalStateException("listing failed");
        }
        return List.copyOf(worlds);
    }

    @Override
    public CompletableFuture<Resolution> load(String world) {
        loadCalls.incrementAndGet();
        return loads.computeIfAbsent(world, w -> new CompletableFuture<>());
    }
}
