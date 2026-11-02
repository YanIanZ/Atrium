package dev.yanianz.atrium.module.sourbycraft;

import dev.iyanz.sourbycraft.api.world.AuroraWorlds;
import dev.iyanz.sourbycraft.api.world.WorldOperationBusyException;
import dev.iyanz.sourbycraft.api.world.WorldProperties;
import dev.yanianz.atrium.api.world.WorldProvider;
import dev.yanianz.atrium.api.world.WorldProviderRegistry;
import dev.yanianz.atrium.api.world.WorldResolver;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jspecify.annotations.Nullable;

/**
 * Aurora World Fabric: publishes the on-demand {@link WorldResolver}, or, when the {@code worlds} module runs,
 * adds AWF as a provider of its chain instead (that chain is then the resolver). Hook class: loaded only on
 * SourbyCraft.
 */
final class WorldHook {
    private WorldHook() {}

    /** The provider registered in the worlds module's chain (to remove on disable), else null. */
    static @Nullable AutoCloseable start(SourbyCraftModule module) {
        ModuleContext ctx = module.context();
        AuroraWorlds worlds = Bukkit.getServicesManager().load(AuroraWorlds.class);
        if (worlds == null) {
            ctx.logger().info("SourbyCraft Aurora World Fabric is not enabled; worlds resolve only when already loaded.");
            return null;
        }
        AwfWorldResolver.Backend backend = new AwfWorldResolver.Backend() {
            @Override
            public @Nullable World loadedWorld(String name) {
                return Bukkit.getWorld(name);
            }

            @Override
            public boolean managed(String name) {
                return worlds.exists(name);
            }

            @Override
            public CompletableFuture<World> load(String name) {
                return worlds.load(name);
            }

            @Override
            public boolean busy(Throwable error) {
                return error instanceof WorldOperationBusyException;
            }
        };
        AwfWorldResolver resolver = new AwfWorldResolver(backend, module::worldSettings, ctx.logger());
        Optional<WorldProviderRegistry> registry = ctx.services().get(WorldProviderRegistry.class);
        if (registry.isPresent()) {
            WorldProvider provider = new AwfWorldProvider(catalog(worlds), resolver, ctx.logger());
            try {
                registry.get().register(provider);
                ctx.logger().info("SourbyCraft Aurora World Fabric detected ({}): AWF worlds join the world provider "
                        + "chain (/wtp, homes, warps and /back load them on demand).", worlds.storage());
                return () -> registry.get().unregister(provider);
            } catch (RuntimeException e) {
                ctx.logger().warn("Adding AWF to the world provider chain failed; publishing the AWF resolver instead", e);
            }
        }
        ctx.service(WorldResolver.class, resolver);
        ctx.logger().info("SourbyCraft Aurora World Fabric detected ({}): homes, warps and /back load AWF worlds on demand.",
                worlds.storage());
        return () -> {};
    }

    private static AwfWorldProvider.Catalog catalog(AuroraWorlds worlds) {
        return new AwfWorldProvider.Catalog() {
            @Override
            public Collection<String> list() {
                return worlds.list();
            }

            @Override
            public @Nullable World loadedWorld(String name) {
                return Bukkit.getWorld(name);
            }

            @Override
            public @Nullable Location spawn(World world) {
                WorldProperties properties = worlds.properties(world.getName());
                WorldProperties.Spawn spawn = properties == null ? null : properties.spawn();
                return spawn == null ? null : new Location(world, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), 0);
            }
        };
    }
}
