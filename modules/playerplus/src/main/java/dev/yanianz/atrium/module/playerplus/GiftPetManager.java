package dev.yanianz.atrium.module.playerplus;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Pet gifting: the command only arms a short window; the actual transfer happens when the owner
 * right-clicks one of their own tamed pets. Both the pet ownership and that the pet is tamed are checked,
 * so a pet can never be taken from someone else or from an untamed animal.
 */
final class GiftPetManager implements Listener {
    private final ModuleContext ctx;
    private final ConfigHandle<PlayerPlusConfig> config;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    GiftPetManager(ModuleContext ctx, ConfigHandle<PlayerPlusConfig> config) {
        this.ctx = ctx;
        this.config = config;
    }

    long windowMillis() {
        return Math.max(1, config.get().giftPet.windowSeconds) * 1000L;
    }

    void arm(Player owner, Player target) {
        pending.put(owner.getUniqueId(),
                new Pending(target.getUniqueId(), target.getName(), System.currentTimeMillis() + windowMillis()));
    }

    void cancel(Player owner) {
        pending.remove(owner.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        Player owner = event.getPlayer();
        Pending request = pending.get(owner.getUniqueId());
        if (request == null) {
            return;
        }
        if (System.currentTimeMillis() > request.expiresAt()) {
            pending.remove(owner.getUniqueId());
            ctx.messages().send(owner, "playerplus.giftpet-expired");
            return;
        }
        if (!(event.getRightClicked() instanceof Tameable pet) || !pet.isTamed()) {
            ctx.messages().send(owner, "playerplus.giftpet-not-tamed");
            return;
        }
        UUID petOwner = pet.getOwnerUniqueId();
        if (petOwner == null || !petOwner.equals(owner.getUniqueId())) {
            ctx.messages().send(owner, "playerplus.giftpet-not-yours");
            return;
        }
        Player target = Bukkit.getPlayer(request.target());
        if (target == null || !target.isOnline()) {
            pending.remove(owner.getUniqueId());
            ctx.messages().send(owner, "playerplus.giftpet-target-left",
                    Msg.unparsed("player", request.targetName()));
            return;
        }
        pet.setOwner(target);
        pet.setTamed(true);
        pending.remove(owner.getUniqueId());
        // Cancel the interaction so mounting or leashing does not also happen on the transfer click.
        event.setCancelled(true);
        ctx.messages().send(owner, "playerplus.giftpet-transferred",
                Msg.unparsed("player", target.getName()), Msg.unparsed("pet", pet.getType().name()));
        ctx.scheduler().supplyOnEntity(target, () -> {
            ctx.messages().send(target, "playerplus.giftpet-received",
                    Msg.unparsed("player", owner.getName()), Msg.unparsed("pet", pet.getType().name()));
            return null;
        }).exceptionally(error -> {
            if (!(error instanceof dev.yanianz.atrium.core.scheduler.Scheduler.EntityRetiredException)) {
                ctx.logger().warn("Notifying {} about a gifted pet failed", target.getName(), error);
            }
            return null;
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    void disable() {
        pending.clear();
    }

    private record Pending(UUID target, String targetName, long expiresAt) {}
}
