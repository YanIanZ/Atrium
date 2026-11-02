package dev.yanianz.atrium.module.worth;

import java.math.BigDecimal;
import java.util.Optional;
import net.brcdev.shopgui.ShopGuiPlusApi;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Sell prices from ShopGUIPlus. This class is only loaded when ShopGUIPlus is enabled, so a server without
 * it never resolves the ShopGUIPlus API.
 *
 * <p>Threading: the price lookup reads the loaded shops and the player's price modifiers, so it is called on
 * the player's region thread, the same thread that owns the item. No call blocks.
 *
 * <p>{@code getItemStackPriceSell} quotes the whole stack (its {@code quantity} included) and returns a
 * negative value when no shop sells the item; that item is refused, never paid at 0.
 */
final class ShopGuiPlusEngine implements PriceEngine {
    static final String PLUGIN = "ShopGUIPlus";

    private ShopGuiPlusEngine() {
    }

    static ShopGuiPlusEngine create() {
        if (!Bukkit.getPluginManager().isPluginEnabled(PLUGIN)) {
            throw new PriceEngineException(PriceEngineSelection.PLUGIN_MISSING);
        }
        try {
            Class<?> api = ShopGuiPlusApi.class;
            api.getName();
        } catch (Throwable error) {
            throw new PriceEngineException("api-missing", error);
        }
        return new ShopGuiPlusEngine();
    }

    @Override
    public PriceEngineKind kind() {
        return PriceEngineKind.SHOPGUIPLUS;
    }

    @Override
    public PriceEngineCapabilities capabilities() {
        return PriceEngineCapabilities.shopGuiPlus();
    }

    @Override
    public Optional<BigDecimal> unitPrice(ItemStack stack, @Nullable Player player) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        double total = player != null && player.isOnline()
                ? ShopGuiPlusApi.getItemStackPriceSell(player, stack)
                : ShopGuiPlusApi.getItemStackPriceSell(stack);
        return EnginePrice.unit(total, stack.getAmount());
    }
}
