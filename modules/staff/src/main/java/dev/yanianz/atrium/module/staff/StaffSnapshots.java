package dev.yanianz.atrium.module.staff;

import java.util.List;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.jspecify.annotations.Nullable;

/** Bukkit glue for {@link StaffSnapshot}: capture a player and read its opaque payloads back. */
final class StaffSnapshots {
    private StaffSnapshots() {
    }

    /**
     * Must run on the player's own thread.
     *
     * @param vanished vanish state before staff mode, {@code null} when no vanish service is installed
     */
    static StaffSnapshot capture(Player player, @Nullable Boolean vanished) {
        var inventory = player.getInventory();
        return new StaffSnapshot(
                StaffSnapshot.CURRENT_FORMAT,
                player.getWorld().getName(),
                player.getLocation().getX(),
                player.getLocation().getY(),
                player.getLocation().getZ(),
                player.getLocation().getYaw(),
                player.getLocation().getPitch(),
                player.getGameMode().name(),
                player.getLevel(),
                player.getExp(),
                player.getTotalExperience(),
                player.getAllowFlight(),
                player.isFlying(),
                ItemStack.serializeItemsAsBytes(inventory.getStorageContents()),
                ItemStack.serializeItemsAsBytes(inventory.getArmorContents()),
                ItemStack.serializeItemsAsBytes(new ItemStack[] {inventory.getItemInOffHand()}),
                PotionEffectCodec.encode(player.getActivePotionEffects()),
                vanished);
    }

    /**
     * Whether the player's storage, armour and off hand serialize to exactly the snapshot's bytes. Player's
     * thread only.
     */
    static boolean inventoryEquals(Player player, StaffSnapshot snapshot) {
        var inventory = player.getInventory();
        return snapshot.itemsEqual(
                ItemStack.serializeItemsAsBytes(inventory.getStorageContents()),
                ItemStack.serializeItemsAsBytes(inventory.getArmorContents()),
                ItemStack.serializeItemsAsBytes(new ItemStack[] {inventory.getItemInOffHand()}));
    }

    static ItemStack[] storage(StaffSnapshot snapshot) {
        return ItemStack.deserializeItemsFromBytes(snapshot.storage());
    }

    static ItemStack[] armor(StaffSnapshot snapshot) {
        return ItemStack.deserializeItemsFromBytes(snapshot.armor());
    }

    static ItemStack[] offhand(StaffSnapshot snapshot) {
        return ItemStack.deserializeItemsFromBytes(snapshot.offhand());
    }

    static List<PotionEffect> effects(StaffSnapshot snapshot, java.util.function.Consumer<String> warn) {
        return PotionEffectCodec.decode(snapshot.effects(), warn);
    }

    static GameMode gameMode(String name) {
        try {
            return GameMode.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            return GameMode.SURVIVAL;
        }
    }

    /** The saved location, or {@code null} when the world is no longer loaded. */
    static @Nullable Location location(StaffSnapshot snapshot) {
        World world = org.bukkit.Bukkit.getWorld(snapshot.world());
        if (world == null) {
            return null;
        }
        return new Location(world, snapshot.x(), snapshot.y(), snapshot.z(), snapshot.yaw(), snapshot.pitch());
    }

    static @Nullable Location location(SpectateState state) {
        World world = org.bukkit.Bukkit.getWorld(state.world());
        if (world == null) {
            return null;
        }
        return new Location(world, state.x(), state.y(), state.z(), state.yaw(), state.pitch());
    }
}
