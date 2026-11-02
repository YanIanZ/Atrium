package dev.yanianz.atrium.module.recipes;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.enchantments.EnchantmentOffer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;

/**
 * Enforces the configured enchantment limits at every source the server can hand an enchanted item from:
 * enchanting table offers, the actual enchant, the anvil result, villager trades and loot tables. Enforcing
 * at the source avoids the case where items kept their forbidden enchantments until the owner closed their
 * inventory). Items already in an inventory are cleaned on join when
 * {@code enchantments.sanitize-on-join} is enabled.
 */
public final class EnchantListener implements Listener {
    private final ModuleContext ctx;
    private final ConfigHandle<RecipesConfig> config;

    public EnchantListener(ModuleContext ctx, ConfigHandle<RecipesConfig> config) {
        this.ctx = ctx;
        this.config = config;
    }

    private RecipesConfig.Enchantments enchantments() {
        return config.get().enchantments;
    }

    private EnchantRules rules(RecipesConfig.Enchantments cfg) {
        return EnchantRules.from(cfg.disabled, cfg.maxLevels);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareEnchant(PrepareItemEnchantEvent event) {
        RecipesConfig.Enchantments cfg = enchantments();
        if (cfg == null || !cfg.enabled || !cfg.enchantingTable) {
            return;
        }
        EnchantRules rules = rules(cfg);
        if (!rules.isActive()) {
            return;
        }
        EnchantmentOffer[] offers = event.getOffers();
        for (int i = 0; i < offers.length; i++) {
            EnchantmentOffer offer = offers[i];
            if (offer == null) {
                continue;
            }
            int allowed = rules.allowedLevel(offer.getEnchantment().getKey().toString(),
                    offer.getEnchantmentLevel());
            if (allowed <= 0) {
                // Paper explicitly allows null offers; the slot is simply empty.
                offers[i] = null;
            } else {
                offer.setEnchantmentLevel(allowed);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        RecipesConfig.Enchantments cfg = enchantments();
        if (cfg == null || !cfg.enabled || !cfg.enchantingTable) {
            return;
        }
        EnchantRules rules = rules(cfg);
        if (!rules.isActive()) {
            return;
        }
        Map<Enchantment, Integer> enchants = event.getEnchantsToAdd();
        if (enchants.isEmpty()) {
            return;
        }
        boolean changed = false;
        try {
            for (Map.Entry<Enchantment, Integer> entry : new HashMap<>(enchants).entrySet()) {
                int allowed = rules.allowedLevel(entry.getKey().getKey().toString(), entry.getValue());
                if (allowed <= 0) {
                    enchants.remove(entry.getKey());
                    changed = true;
                } else if (allowed != entry.getValue()) {
                    enchants.put(entry.getKey(), allowed);
                    changed = true;
                }
            }
        } catch (UnsupportedOperationException e) {
            // A plugin supplied an immutable map; never let a forbidden enchantment through.
            ctx.logger().error("Cannot apply the enchantment limits to EnchantItemEvent; cancelling", e);
            event.setCancelled(true);
            return;
        }
        if (changed && enchants.isEmpty()) {
            // Every offered enchantment was removed; cancel instead of charging levels for a plain item.
            event.setCancelled(true);
            Player player = event.getEnchanter();
            ctx.scheduler().entity(player, () -> {
                if (player.isOnline()) {
                    ctx.messages().send(player, "recipes.enchant-unavailable");
                }
            }, null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        RecipesConfig.Enchantments cfg = enchantments();
        if (cfg == null || !cfg.enabled || !cfg.anvil) {
            return;
        }
        EnchantRules rules = rules(cfg);
        if (!rules.isActive()) {
            return;
        }
        ItemStack result = event.getResult();
        EnchantSanitizer.Result sanitized = EnchantSanitizer.sanitize(result, rules);
        if (!sanitized.changed()) {
            return;
        }
        if (cfg.anvilClearDisabled && sanitized.disabledFound()) {
            event.setResult(null);
            return;
        }
        event.setResult(sanitized.item());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVillagerTrade(VillagerAcquireTradeEvent event) {
        RecipesConfig.Enchantments cfg = enchantments();
        if (cfg == null || !cfg.enabled || !cfg.villagerTrades) {
            return;
        }
        EnchantRules rules = rules(cfg);
        if (!rules.isActive()) {
            return;
        }
        MerchantRecipe old = event.getRecipe();
        ItemStack result = old.getResult();
        EnchantSanitizer.Result sanitized = EnchantSanitizer.sanitize(result, rules);
        if (!sanitized.changed()) {
            return;
        }
        ItemStack replacement = sanitized.item();
        if (replacement == null) {
            return;
        }
        if (cfg.replaceEmptyBookTrades && replacement.getType() == Material.ENCHANTED_BOOK
                && !EnchantSanitizer.hasAnyEnchant(replacement)) {
            replacement = new ItemStack(Material.BOOK);
        }
        event.setRecipe(copyWithResult(old, replacement));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLoot(LootGenerateEvent event) {
        RecipesConfig.Enchantments cfg = enchantments();
        if (cfg == null || !cfg.enabled || !cfg.loot) {
            return;
        }
        EnchantRules rules = rules(cfg);
        if (!rules.isActive()) {
            return;
        }
        List<ItemStack> loot = event.getLoot();
        if (loot.isEmpty()) {
            return;
        }
        List<ItemStack> copied = new ArrayList<>(loot.size());
        boolean changed = false;
        for (ItemStack item : loot) {
            EnchantSanitizer.Result sanitized = EnchantSanitizer.sanitize(item, rules);
            if (sanitized.changed() && sanitized.item() != null) {
                copied.add(sanitized.item());
                changed = true;
            } else {
                copied.add(item);
            }
        }
        if (changed) {
            event.setLoot(copied);
        }
    }

    private static MerchantRecipe copyWithResult(MerchantRecipe old, ItemStack result) {
        MerchantRecipe recipe = new MerchantRecipe(result, old.getMaxUses());
        recipe.setUses(old.getUses());
        recipe.setExperienceReward(old.hasExperienceReward());
        recipe.setVillagerExperience(old.getVillagerExperience());
        recipe.setPriceMultiplier(old.getPriceMultiplier());
        recipe.setDemand(old.getDemand());
        recipe.setSpecialPrice(old.getSpecialPrice());
        recipe.setIgnoreDiscounts(old.shouldIgnoreDiscounts());
        recipe.setIngredients(new ArrayList<>(old.getIngredients()));
        return recipe;
    }
}
