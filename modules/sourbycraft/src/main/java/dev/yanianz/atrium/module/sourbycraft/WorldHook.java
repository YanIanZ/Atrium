package dev.yanianz.atrium.module.sourbycraft;

import dev.iyanz.sourbycraft.api.world.AuroraWorlds;
import dev.iyanz.sourbycraft.api.world.WorldOperationBusyException;
import dev.yanianz.atrium.api.world.WorldResolver;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jspecify.annotations.Nullable;

/** Aurora World Fabric: publishes the on-demand {@link WorldResolver}. Hook class: loaded only on SourbyCraft. */
final class WorldHook {
    private WorldHook() {}

    /** True when the resolver was published. */
    static boolean start(SourbyCraftModule module) {
        ModuleContext ctx = module.context();
        AuroraWorlds worlds = Bukkit.getServicesManager().load(AuroraWorlds.class);
        if (worlds == null) {
            ctx.logger().info("SourbyCraft Aurora World Fabric is not enabled; worlds resolve only when already loaded.");
            return false;
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
        ctx.service(WorldResolver.class, new AwfWorldResolver(backend, module::worldSettings, ctx.logger()));
        ctx.logger().info("SourbyCraft Aurora World Fabric detected ({}): homes, warps and /back load AWF worlds on demand.",
                worlds.storage());
        return true;
    }
}
