package dev.yanianz.atrium.module.armorstand;

import dev.yanianz.atrium.api.armorstand.ArmorStandEditEvent;
import dev.yanianz.atrium.api.protection.ProtectionService;
import dev.yanianz.atrium.core.config.ConfigHandle;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.jspecify.annotations.Nullable;

/** Finding the targeted stand and asking other plugins for permission. Player's region thread only. */
final class StandAccess {
    enum Lookup { FOUND, NONE, ELSEWHERE }

    record Target(Lookup lookup, @Nullable ArmorStand stand) {}

    private final ConfigHandle<ArmorStandConfig> config;
    private final ProtectionService protection;

    StandAccess(ConfigHandle<ArmorStandConfig> config, ProtectionService protection) {
        this.config = config;
        this.protection = protection;
    }

    double reach() {
        return config.get().reach();
    }

    /** Ray traces from the eyes; blocks stop the ray. The stand must belong to the current region. */
    Target find(Player player) {
        Location eye = player.getEyeLocation();
        RayTraceResult hit = player.getWorld().rayTrace(eye, eye.getDirection(), reach(), FluidCollisionMode.NEVER,
                true, 0.1, e -> e instanceof ArmorStand);
        if (hit == null || !(hit.getHitEntity() instanceof ArmorStand stand)) {
            return new Target(Lookup.NONE, null);
        }
        if (!Bukkit.isOwnedByCurrentRegion(stand)) {
            return new Target(Lookup.ELSEWHERE, null);
        }
        return new Target(Lookup.FOUND, stand);
    }

    /** Still valid, owned by this thread's region and within reach of the player. */
    boolean usable(Player player, ArmorStand stand) {
        if (!player.isOnline() || !stand.isValid() || !Bukkit.isOwnedByCurrentRegion(stand)
                || !stand.getWorld().equals(player.getWorld())) {
            return false;
        }
        double max = reach() + 1.5;
        return stand.getLocation().distanceSquared(player.getEyeLocation()) <= max * max;
    }

    /**
     * Fires Atrium's cancellable {@link ArmorStandEditEvent} and, unless disabled, Bukkit's
     * {@link PlayerArmorStandManipulateEvent} (handled by protection plugins). Items passed are copies.
     */
    boolean permitted(Player player, ArmorStand stand, ArmorStandEditEvent.Action action, String detail) {
        if (config.get().protectionServiceCheck && protection != null
                && !protection.canInteract(player, stand.getLocation())) {
            return false;
        }
        var event = new ArmorStandEditEvent(player, stand, action, detail);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return false;
        }
        if (!config.get().protectionCheck) {
            return true;
        }
        ItemStack hand = player.getInventory().getItemInMainHand().clone();
        ItemStack worn = stand.getEquipment().getItem(EquipmentSlot.HAND).clone();
        var manipulate = new PlayerArmorStandManipulateEvent(player, stand, hand, worn, EquipmentSlot.HAND,
                EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(manipulate);
        return !manipulate.isCancelled();
    }
}
