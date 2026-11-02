package dev.yanianz.atrium.module.worth;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.gypopo.economyshopgui.api.EconomyShopGUIHook;
import me.gypopo.economyshopgui.api.objects.SellPrice;
import me.gypopo.economyshopgui.objects.ShopItem;
import me.gypopo.economyshopgui.util.EcoType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Sell prices from EconomyShopGUI (free or premium). This class is only loaded when one of those plugins
 * is enabled, so a server without it never resolves the EconomyShopGUI API.
 *
 * <p>Threading: the API reads the loaded shops and the player's permission/multiplier data, so it is called
 * on the player's region thread, the same thread that owns the item. No call blocks.
 *
 * <p>Only the Vault price is used: Atrium pays through its own economy, so a quote in another currency is
 * refused instead of being paid as money. EconomyShopGUI's stock and dynamic-price state is only read, never
 * consumed, because Atrium removes the items and pays the sale itself.
 */
final class EconomyShopGuiEngine implements PriceEngine {
    /** Free first, premium second; both expose the same API. */
    static final List<String> PLUGINS = List.of("EconomyShopGUI", "EconomyShopGUI-Premium");

    private EconomyShopGuiEngine() {
    }

    static EconomyShopGuiEngine create() {
        String enabled = PLUGINS.stream().filter(name -> Bukkit.getPluginManager().isPluginEnabled(name))
                .findFirst().orElse(null);
        if (enabled == null) {
            throw new PriceEngineException(PriceEngineSelection.PLUGIN_MISSING);
        }
        try {
            // Resolve the API now so an old EconomyShopGUI without it falls back at startup, not mid-sale.
            Class<?> api = EconomyShopGUIHook.class;
            api.getName();
        } catch (Throwable error) {
            throw new PriceEngineException("api-missing", error);
        }
        return new EconomyShopGuiEngine();
    }

    @Override
    public PriceEngineKind kind() {
        return PriceEngineKind.ECONOMYSHOPGUI;
    }

    @Override
    public PriceEngineCapabilities capabilities() {
        return PriceEngineCapabilities.economyShopGui();
    }

    @Override
    public Optional<BigDecimal> unitPrice(ItemStack stack, @Nullable Player player) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        if (player != null && player.isOnline()) {
            Optional<SellPrice> quote = EconomyShopGUIHook.getSellPrice(player, stack);
            if (quote.isEmpty()) {
                return Optional.empty();
            }
            SellPrice price = quote.get();
            Map<String, Double> byCurrency = new LinkedHashMap<>();
            for (Map.Entry<EcoType, Double> entry : price.getPrices().entrySet()) {
                byCurrency.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            Optional<BigDecimal> total = EnginePrice.vaultPrice(byCurrency);
            return total.isEmpty() ? Optional.empty() : EnginePrice.unit(total.get(), price.getAmount());
        }
        // No seller (console, other plugins): use the shop-wide price without permission/multiplier checks.
        ShopItem item = EconomyShopGUIHook.getShopItem(stack);
        if (item == null || item.hasItemError() || item.isDisplayItem()
                || !EconomyShopGUIHook.isSellAble(item)) {
            return Optional.empty();
        }
        Optional<BigDecimal> total = EnginePrice.nullable(EconomyShopGUIHook.getItemSellPrice(item, stack));
        return total.isEmpty() ? Optional.empty() : EnginePrice.unit(total.get(), stack.getAmount());
    }
}
