package dev.yanianz.atrium.module.sourbycraft;

import dev.iyanz.sourbycraft.api.event.EntityPostTeleportAsyncEvent;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * SourbyCraft's {@link EntityPostTeleportAsyncEvent} (a finished async teleport, as a profile switch makes) for
 * players. Separate class: registered only when the event class exists.
 */
final class ProfileTeleportListener implements Listener {
    private final Consumer<Player> teleported;

    ProfileTeleportListener(Consumer<Player> teleported) {
        this.teleported = teleported;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleported(EntityPostTeleportAsyncEvent event) {
        if (event.getEntity() instanceof Player player) {
            teleported.accept(player);
        }
    }
}
