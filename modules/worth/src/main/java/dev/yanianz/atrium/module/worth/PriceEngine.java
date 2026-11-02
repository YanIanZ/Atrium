package dev.yanianz.atrium.module.worth;

import java.math.BigDecimal;
import java.util.Optional;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * A source of sell prices for one unit of an item. Implementations are called on the thread that owns the
 * item (the player's region thread, or the caller's thread for {@code WorthService}), so no engine may block.
 */
interface PriceEngine {
    PriceEngineKind kind();

    PriceEngineCapabilities capabilities();

    /**
     * Sell value of one unit of {@code stack}, or empty when this engine does not sell the item. An engine
     * that does not know an item must refuse it; never return zero as a "no price".
     *
     * @param player the seller, {@code null} when the price is requested without one (console, other plugins)
     */
    Optional<BigDecimal> unitPrice(ItemStack stack, @Nullable Player player) throws PriceEngineException;
}
