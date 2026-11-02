package dev.yanianz.atrium.api.worldrules;

import org.bukkit.entity.Player;

/**
 * Re-evaluation hook for the armour sets managed by the {@code worldrules} module. Published by that module.
 *
 * <p>The module already reacts to Paper's {@code EntityEquipmentChangedEvent}, which covers inventory
 * clicks, dispensers, pickups and command-driven swaps such as {@code /hat}. This service exists for
 * plugins that change equipment without a detectable equipment update, and for code that needs a
 * deterministic refresh after its own item change.
 */
public interface ArmorEffectService {
    /**
     * Schedules one evaluation of the player's armour set on the player's own thread. Safe from any thread;
     * a player that is offline or has no configured set is ignored.
     */
    void reevaluate(Player player);
}
