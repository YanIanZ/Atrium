package dev.yanianz.atrium.module.worth;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The price table in {@code worth.yml}. Matching, durability and enchantment rules live in
 * {@link PriceTable}; this engine only reads the item on the calling thread and values it.
 */
final class AtriumPriceEngine implements PriceEngine {
    private final Supplier<PriceTable> prices;
    private final Supplier<ItemFactsReader> reader;

    AtriumPriceEngine(Supplier<PriceTable> prices, Supplier<ItemFactsReader> reader) {
        this.prices = prices;
        this.reader = reader;
    }

    @Override
    public PriceEngineKind kind() {
        return PriceEngineKind.ATRIUM;
    }

    @Override
    public PriceEngineCapabilities capabilities() {
        return PriceEngineCapabilities.atrium();
    }

    @Override
    public Optional<BigDecimal> unitPrice(ItemStack stack, @Nullable Player player) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        return prices.get().unitPrice(reader.get().read(stack));
    }
}
