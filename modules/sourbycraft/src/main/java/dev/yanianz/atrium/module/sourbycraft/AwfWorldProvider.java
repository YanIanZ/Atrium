package dev.yanianz.atrium.module.sourbycraft;

import dev.yanianz.atrium.api.world.KnownWorld;
import dev.yanianz.atrium.api.world.WorldProvider;
import dev.yanianz.atrium.api.world.WorldResolver.Resolution;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.World;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Aurora World Fabric as a {@link WorldProvider} of the worlds module's chain. Loading goes through
 * {@link AwfWorldResolver} (shared loads, timeout, {@code worlds-load-on-demand}, {@code worlds-on-demand-deny});
 * the spawn is AWF's stored spawn when set. Like the resolver, this class has no SourbyCraft types: the
 * platform calls are behind {@link Catalog}.
 */
final class AwfWorldProvider implements WorldProvider {
    /** The platform side. */
    interface Catalog {
        /** Names of all AWF worlds (AuroraWorlds.list). */
        Collection<String> list();

        /** The world if it is loaded (Bukkit.getWorld). */
        @Nullable World loadedWorld(String name);

        /** AWF's stored spawn of the world, null when none is set. */
        @Nullable Location spawn(World world);
    }

    private final Catalog catalog;
    private final AwfWorldResolver resolver;
    private final Logger logger;

    AwfWorldProvider(Catalog catalog, AwfWorldResolver resolver, Logger logger) {
        this.catalog = catalog;
        this.resolver = resolver;
        this.logger = logger;
    }

    @Override
    public String id() {
        return AWF;
    }

    @Override
    public String displayName() {
        return "AWF";
    }

    @Override
    public List<KnownWorld> knownWorlds() {
        Collection<String> names;
        try {
            names = catalog.list();
        } catch (RuntimeException e) {
            logger.warn("Listing the AWF worlds failed", e);
            return List.of();
        }
        List<KnownWorld> result = new ArrayList<>(names.size());
        for (String name : names) {
            World world = catalog.loadedWorld(name);
            result.add(new KnownWorld(name, AWF, world != null, "", world == null ? null : world.getEnvironment()));
        }
        return result;
    }

    @Override
    public CompletableFuture<Resolution> load(String world) {
        return resolver.lookup(world);
    }

    @Override
    public Optional<Location> spawn(World world) {
        try {
            return Optional.ofNullable(catalog.spawn(world));
        } catch (RuntimeException e) {
            logger.debug("Reading the AWF spawn of {} failed", world.getName(), e);
            return Optional.empty();
        }
    }
}
