package dev.yanianz.atrium.api.worldrules;

import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Fired on the player's own thread after one armour slot changed (worn, swapped, broken or removed).
 *
 * <p>The event is not cancellable on purpose: Paper's equipment change is a notification that has already
 * happened, so cancelling it could not put the old item back and would leave plugins disagreeing about what
 * the player wears. A plugin that wants to veto an armour change cancels the inventory click, dispenser or
 * entity interaction before it happens instead. This event is for observers (stats, cosmetics, set bonuses).
 *
 * <p>{@code oldItem} and {@code newItem} are copies taken when the change happened; changing them has no
 * effect on the player.
 */
public final class ArmorChangeEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    private final EquipmentSlot slot;
    private final ItemStack oldItem;
    private final ItemStack newItem;

    public ArmorChangeEvent(Player player, EquipmentSlot slot, ItemStack oldItem, ItemStack newItem) {
        super(Objects.requireNonNull(player, "player"));
        this.slot = Objects.requireNonNull(slot, "slot");
        if (!isArmorSlot(slot)) {
            throw new IllegalArgumentException(slot + " is not an armour slot");
        }
        this.oldItem = Objects.requireNonNull(oldItem, "oldItem").clone();
        this.newItem = Objects.requireNonNull(newItem, "newItem").clone();
    }

    /** HEAD, CHEST, LEGS or FEET. */
    public EquipmentSlot slot() {
        return slot;
    }

    /** The armour piece before the change (AIR when the slot was empty). */
    public ItemStack oldItem() {
        return oldItem.clone();
    }

    /** The armour piece after the change (AIR when it was removed). */
    public ItemStack newItem() {
        return newItem.clone();
    }

    /** Player convenience mirror of {@link PlayerEvent#getPlayer()}. */
    public Player player() {
        return getPlayer();
    }

    /** True for the four slots this event is fired for. */
    public static boolean isArmorSlot(EquipmentSlot slot) {
        return slot == EquipmentSlot.HEAD || slot == EquipmentSlot.CHEST
                || slot == EquipmentSlot.LEGS || slot == EquipmentSlot.FEET;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
