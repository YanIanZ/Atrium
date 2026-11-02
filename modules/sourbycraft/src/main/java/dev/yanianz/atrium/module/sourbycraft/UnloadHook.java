package dev.yanianz.atrium.module.sourbycraft;

import dev.iyanz.sourbycraft.api.event.world.WorldUnloadAsyncEvent;
import dev.yanianz.atrium.api.world.WorldUnloadingEvent;
import dev.yanianz.atrium.core.module.ModuleContext;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Turns SourbyCraft's {@link WorldUnloadAsyncEvent} (global region thread) into Atrium's
 * {@link WorldUnloadingEvent}, so modules drop state tied to the world. Hook class: loaded only on SourbyCraft.
 */
final class UnloadHook implements Listener {
    private final ModuleContext ctx;

    private UnloadHook(ModuleContext ctx) {
        this.ctx = ctx;
    }

    static void start(SourbyCraftModule module) {
        module.context().listen(new UnloadHook(module.context()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onUnload(WorldUnloadAsyncEvent event) {
        String world = event.getWorld().getName();
        try {
            new WorldUnloadingEvent(world, "sourbycraft:WorldUnloadAsyncEvent").callEvent();
        } catch (RuntimeException e) {
            ctx.logger().warn("Notifying Atrium modules that world {} unloads failed", world, e);
        }
    }
}
