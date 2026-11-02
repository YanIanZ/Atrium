package dev.yanianz.atrium.api.worth;

import java.math.BigDecimal;
import java.util.Optional;
import org.bukkit.inventory.ItemStack;

/**
 * Item sell prices of Atrium's worth module. Matching is exact: an item carrying custom model data, an item
 * model, a custom name, lore, enchantments or other custom data only matches a price entry that specifies
 * (or explicitly accepts) that data. Call from the thread that owns the item (the holder's region thread).
 */
public interface WorthService {
    /** Sell value of a single item of this stack (amount ignored), or empty when it cannot be sold. */
    Optional<BigDecimal> unitPrice(ItemStack item);

    /** Sell value of the whole stack (unit price times amount), or empty when it cannot be sold. */
    default Optional<BigDecimal> stackPrice(ItemStack item) {
        return unitPrice(item).map(unit -> unit.multiply(BigDecimal.valueOf(item.getAmount())));
    }
}
