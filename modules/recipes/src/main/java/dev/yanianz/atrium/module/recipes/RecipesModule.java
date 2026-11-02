package dev.yanianz.atrium.module.recipes;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionDefault;

/**
 * Custom recipes from {@code plugins/Atrium/modules/recipes/*.yml}, per-enchantment limits and anvil rules.
 *
 * <p>Recipes are registered as {@code atrium:<id>} on the global thread, removed before a reload and
 * re-added from the newly parsed files. A broken file is skipped with one log line and never blocks the
 * other recipes. Enchantment rules are enforced where the server hands out the item (table, anvil, villager,
 * loot) instead of only when an inventory closes.
 */
public final class RecipesModule implements AtriumModule, Listener {
    static final String PERM_RECIPE = "atrium.customrecipe";
    static final String PERM_LIST = "atrium.customrecipe.list";
    static final String PERM_RELOAD = "atrium.customrecipe.reload";
    static final String PERM_GIVE = "atrium.customrecipe.give";
    static final String PERM_GIVE_OTHERS = "atrium.customrecipe.give.others";
    static final String PERM_DISCOVER = "atrium.recipes.discover";
    static final String PERM_ANVIL_BYPASS = "atrium.recipes.anvil.bypass";
    static final String PERM_ANVIL_COLOR = "atrium.recipes.anvil.color";
    static final String PERM_ANVIL_FORMAT = "atrium.recipes.anvil.format";

    private ModuleContext ctx;
    private ConfigHandle<RecipesConfig> config;
    private RecipeRegistrar registrar;
    private Path folder;
    private volatile boolean disabled;

    @Override
    public String id() {
        return "recipes";
    }

    @Override
    public String description() {
        return "Custom YAML recipes, enchantment limits and anvil rules";
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.disabled = false;
        this.config = ctx.config(RecipesConfig.class, RecipesConfig::new);
        this.folder = ctx.plugin().getDataPath().resolve("modules").resolve("recipes");
        this.registrar = new RecipeRegistrar(ctx, new RecipeBuilder(ctx));

        ctx.permission(PERM_RECIPE, PermissionDefault.TRUE, "Use /customrecipe");
        ctx.permission(PERM_LIST, PermissionDefault.TRUE, "List custom recipes");
        ctx.permission(PERM_RELOAD, PermissionDefault.OP, "Reload the custom recipe files");
        ctx.permission(PERM_GIVE, PermissionDefault.OP, "Give yourself a custom recipe result");
        ctx.permission(PERM_GIVE_OTHERS, PermissionDefault.OP, "Give a custom recipe result to another player");
        ctx.permission(PERM_DISCOVER, PermissionDefault.TRUE, "See custom recipes in the recipe book");
        ctx.permission(PERM_ANVIL_BYPASS, PermissionDefault.OP, "Bypass the anvil repair cost limit");
        ctx.permission(PERM_ANVIL_COLOR, PermissionDefault.TRUE, "Use colour tags in anvil renames");
        ctx.permission(PERM_ANVIL_FORMAT, PermissionDefault.TRUE, "Use decoration tags in anvil renames");

        ctx.listen(this);
        ctx.listen(new EnchantListener(ctx, config));
        ctx.listen(new AnvilListener(config));
        new RecipesCommands(this).registerRoot();
        validateEnchantmentConfig();

        // Startup may not be on the global region thread; file reading is blocking and is done here once.
        RecipeLoader.Result loaded;
        try {
            loaded = RecipeLoader.load(folder);
        } catch (IOException e) {
            ctx.logger().error("Cannot read the recipe folder {}; starting without custom recipes", folder, e);
            loaded = RecipeLoader.Result.empty();
        }
        RecipeLoader.Result initial = loaded;
        ctx.scheduler().supplyGlobal(() -> install(initial))
                .whenComplete((result, error) -> {
                    if (error != null) {
                        ctx.logger().error("Registering the custom recipes failed", error);
                    } else if (result != null) {
                        ctx.logger().info("Loaded {} custom recipes ({} registered, {} problem(s))",
                                result.parsed(), result.registered(), result.problems());
                    }
                });
    }

    @Override
    public void reload() {
        validateEnchantmentConfig();
        reloadAsync().whenComplete((result, error) -> {
            if (error != null) {
                ctx.logger().error("Reloading the custom recipes failed; the previous recipes stay active", error);
            } else if (result != null) {
                ctx.logger().info("Reloaded {} custom recipes ({} registered, {} problem(s))",
                        result.parsed(), result.registered(), result.problems());
            }
        });
    }

    @Override
    public void disable() {
        disabled = true;
        if (registrar != null) {
            registrar.unregisterAll();
        }
    }

    /**
     * Reads the folder on an I/O thread and applies it on the global thread. Used by /customrecipe reload and
     * by /atrium reload, so no region thread ever waits for the filesystem.
     */
    CompletableFuture<InstallResult> reloadAsync() {
        return ctx.scheduler().supplyIo(() -> RecipeLoader.load(folder))
                .thenCompose(loaded -> ctx.scheduler().supplyGlobal(() -> install(loaded)));
    }

    /** Runs on the global thread. */
    private InstallResult install(RecipeLoader.Result loaded) {
        if (disabled) {
            return new InstallResult(0, 0, 0);
        }
        loaded.errors().forEach(line -> ctx.logger().warn("Recipe file skipped: {}", line));
        if (!config.get().customRecipes) {
            RecipeRegistrar.ApplyResult removed = registrar.replace(Map.of());
            resyncDiscovery(removed.previousKeys());
            return new InstallResult(0, 0, loaded.errors().size());
        }
        RecipeRegistrar.ApplyResult applied = registrar.replace(loaded.recipes());
        applied.errors().forEach(line -> ctx.logger().warn("Recipe skipped: {}", line));
        resyncDiscovery(applied.previousKeys());
        return new InstallResult(loaded.recipes().size(), applied.registered(),
                loaded.errors().size() + applied.errors().size());
    }

    /**
     * Syncs the recipe book with the newly registered keys. Removed recipes are undiscovered even when
     * auto-discover is off, so a reload cannot leave a ghost recipe behind.
     */
    private void resyncDiscovery(Set<NamespacedKey> previous) {
        Set<NamespacedKey> snapshot = registrar.keys();
        boolean discover = config.get().autoDiscover;
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            boolean permitted = discover && player.hasPermission(PERM_DISCOVER);
            if (previous.isEmpty() && (!permitted || snapshot.isEmpty())) {
                continue;
            }
            ctx.scheduler().entity(player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                // Read the live keys inside the task: another reload may have finished in the meantime.
                Set<NamespacedKey> current = registrar.keys();
                Set<NamespacedKey> removed = new java.util.LinkedHashSet<>(previous);
                removed.removeAll(current);
                try {
                    if (!removed.isEmpty()) {
                        player.undiscoverRecipes(removed);
                    }
                    if (permitted && !current.isEmpty()) {
                        player.discoverRecipes(current);
                    }
                } catch (RuntimeException e) {
                    ctx.logger().warn("Could not update the recipe book of {}", player.getName(), e);
                }
            }, null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        RecipesConfig cfg = config.get();
        RecipesConfig.Enchantments enchantments = cfg.enchantments;
        EnchantRules rules = enchantments != null && enchantments.enabled && enchantments.sanitizeOnJoin
                ? EnchantRules.from(enchantments.disabled, enchantments.maxLevels)
                : EnchantRules.empty();
        boolean sanitize = rules.isActive();
        boolean discover = cfg.autoDiscover && player.hasPermission(PERM_DISCOVER) && !registrar.keys().isEmpty();
        if (!sanitize && !discover) {
            return;
        }
        ctx.scheduler().entity(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (sanitize) {
                sanitize(player, rules);
            }
            if (discover) {
                Set<NamespacedKey> keys = registrar.keys();
                if (!keys.isEmpty()) {
                    try {
                        player.discoverRecipes(keys);
                    } catch (RuntimeException e) {
                        ctx.logger().warn("Could not add the custom recipes to the recipe book of {}",
                                player.getName(), e);
                    }
                }
            }
        }, null);
    }

    private void sanitize(Player player, EnchantRules rules) {
        int changed = 0;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (sanitizeSlot(inventory, slot, rules)) {
                changed++;
            }
        }
        Inventory enderChest = player.getEnderChest();
        for (int slot = 0; slot < enderChest.getSize(); slot++) {
            if (sanitizeSlot(enderChest, slot, rules)) {
                changed++;
            }
        }
        if (changed > 0) {
            ctx.logger().info("Removed or capped forbidden enchantments in {} item slot(s) of {}",
                    changed, player.getName());
            ctx.messages().send(player, "recipes.enchants-cleaned");
        }
    }

    private static boolean sanitizeSlot(Inventory inventory, int slot, EnchantRules rules) {
        ItemStack item = inventory.getItem(slot);
        if (item == null || item.getType().isAir()) {
            return false;
        }
        EnchantSanitizer.Result result = EnchantSanitizer.sanitize(item, rules);
        if (result.changed() && result.item() != null) {
            inventory.setItem(slot, result.item());
            return true;
        }
        return false;
    }

    /** Warns about enchantment names or caps that can never match, instead of silently ignoring them. */
    private void validateEnchantmentConfig() {
        RecipesConfig.Enchantments cfg = config.get().enchantments;
        if (cfg == null) {
            return;
        }
        List<String> disabled = cfg.disabled == null ? List.of() : cfg.disabled;
        Map<String, Integer> levels = cfg.maxLevels == null ? Map.of() : cfg.maxLevels;
        Set<String> raw = new LinkedHashSet<>(disabled);
        raw.addAll(levels.keySet());
        for (String entry : raw) {
            String key = EnchantRules.normalize(entry);
            if (key.isEmpty()) {
                ctx.logger().warn("recipes.yml: an empty enchantment name in the rules is ignored");
                continue;
            }
            try {
                NamespacedKey namespaced = NamespacedKey.fromString(key);
                if (namespaced == null || io.papermc.paper.registry.RegistryAccess.registryAccess()
                        .getRegistry(io.papermc.paper.registry.RegistryKey.ENCHANTMENT)
                        .get(namespaced) == null) {
                    ctx.logger().warn("recipes.yml: unknown enchantment '{}' in the rules", entry);
                }
            } catch (IllegalArgumentException e) {
                ctx.logger().warn("recipes.yml: invalid enchantment name '{}' in the rules", entry);
            }
        }
        levels.forEach((key, level) -> {
            if (level == null || level < 1) {
                ctx.logger().warn("recipes.yml: max-levels.{} is not a positive level and is ignored", key);
            }
        });
    }

    ModuleContext context() {
        return ctx;
    }

    RecipeRegistrar registrar() {
        return registrar;
    }

    ConfigHandle<RecipesConfig> config() {
        return config;
    }

    /** One reload/install result. {@code problems} counts skipped files and rejected recipes. */
    record InstallResult(int parsed, int registered, int problems) {}
}
