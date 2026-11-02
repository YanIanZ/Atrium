package dev.yanianz.atrium.module.recipes;

import dev.yanianz.atrium.core.config.ConfigHandle;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;

/**
 * Anvil rules: lift the vanilla 40-level "Too expensive!" cap for players with the bypass permission and
 * allow restricted MiniMessage colours/decorations in renames. Rename text always goes through
 * {@link AnvilText}; it is never parsed as full MiniMessage and never passed to PlaceholderAPI.
 */
public final class AnvilListener implements Listener {
    private final ConfigHandle<RecipesConfig> config;

    public AnvilListener(ConfigHandle<RecipesConfig> config) {
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        AnvilView view = event.getView();
        if (!(view.getPlayer() instanceof Player player)) {
            return;
        }
        RecipesConfig.Anvil cfg = config.get().anvil;
        if (cfg == null) {
            return;
        }

        if (cfg.bypassRepairCost && player.hasPermission(RecipesModule.PERM_ANVIL_BYPASS)) {
            int cost = view.getRepairCost();
            if (cost >= view.getMaximumRepairCost()) {
                int configured = Math.clamp(cfg.bypassMaxCost, 40, 1_000_000);
                // Give the current cost room too, so a very high configured limit can never block itself.
                view.setMaximumRepairCost(Math.max(configured, cost + 1));
            }
        }
        if (cfg.bypassEnchantmentLevelLimit && player.hasPermission(RecipesModule.PERM_ANVIL_BYPASS)) {
            view.bypassEnchantmentLevelRestriction(true);
        }

        if (!cfg.coloredRenames) {
            return;
        }
        boolean colors = cfg.coloredRenames && player.hasPermission(RecipesModule.PERM_ANVIL_COLOR);
        boolean decorations = cfg.decoratedRenames && player.hasPermission(RecipesModule.PERM_ANVIL_FORMAT);
        if (!colors && !decorations) {
            return;
        }
        String rename = view.getRenameText();
        if (rename == null || rename.indexOf('<') < 0) {
            return;
        }
        ItemStack result = event.getResult();
        if (result == null || result.getType().isAir()) {
            return;
        }
        Component formatted = AnvilText.format(rename, colors, decorations,
                Math.clamp(cfg.maxRenameLength, 1, 256));
        if (formatted == null) {
            return;
        }
        ItemStack renamed = result.clone();
        ItemMeta meta = renamed.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.displayName(formatted);
        if (renamed.setItemMeta(meta)) {
            event.setResult(renamed);
        }
    }
}
